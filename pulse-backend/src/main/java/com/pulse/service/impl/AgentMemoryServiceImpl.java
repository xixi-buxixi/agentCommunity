package com.pulse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.config.MemoryProperties;
import com.pulse.dto.AgentActionOutcome;
import com.pulse.dto.AgentMemoryCard;
import com.pulse.dto.ReflectionContext;
import com.pulse.dto.ReflectionResult;
import com.pulse.dto.request.AgentMemoryUpdateRequest;
import com.pulse.dto.response.AgentMemoryResponse;
import com.pulse.entity.Agent;
import com.pulse.entity.AgentLog;
import com.pulse.entity.AgentMemory;
import com.pulse.enums.MemoryStatus;
import com.pulse.enums.MemoryType;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.AgentLogMapper;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.AgentMemoryMapper;
import com.pulse.service.AgentMemoryService;
import com.pulse.service.AgentProfileService;
import com.pulse.service.support.AuthorResolver;
import com.pulse.util.MemoryTextSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent Memory Service Implementation
 *
 * Two halves that must not be confused:
 * - the owner-facing read/patch path, which validates ownership and throws;
 * - the hot-path write, which runs after the action transaction and swallows every
 *   failure (see {@link #recordActionMemories}).
 */
@Slf4j
@Service
public class AgentMemoryServiceImpl implements AgentMemoryService {

    /** Hard cap for a card body. Longer templates are truncated, never rejected. */
    static final int CONTENT_MAX_LENGTH = 200;

    /** Cap for the quoted headline part of a template. */
    private static final int HEADLINE_MAX_LENGTH = 30;

    /** Cap for the quoted body part of a template. */
    private static final int SEGMENT_MAX_LENGTH = 120;

    /** Cap for an owner-supplied correction. */
    private static final int USER_CONTENT_MAX_LENGTH = 500;

    /** Cap for the interpolated author name. */
    private static final int AUTHOR_LABEL_MAX_LENGTH = 30;

    private static final int DEFAULT_IMPORTANCE = 50;
    private static final int DEFAULT_CONFIDENCE = 90;

    private static final String CREATED_BY_SYSTEM = "SYSTEM";
    private static final String CREATED_BY_USER_EDIT = "USER_EDIT";
    private static final String SCOPE_SELF = "SELF";

    /**
     * Visibility scope of a card the owner published on the agent's public profile.
     *
     * The column has existed since phase 1 and was constant at SELF; PUBLIC is the
     * second value it takes. Everything that reads a memory for the agent's own use
     * (injection, reflection) ignores scope entirely - publishing changes who can SEE
     * a card, never what the agent does with it.
     */
    private static final String SCOPE_PUBLIC = "PUBLIC";

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    /** Cap for a reflection-supplied evidence note. */
    private static final int EVIDENCE_MAX_LENGTH = 300;

    /** Cap for one behaviour line in the reflection pack. */
    private static final int BEHAVIOR_LINE_MAX_LENGTH = 200;

    /** Confidence assumed when the reflection model reports none. */
    private static final int DEFAULT_TRAIT_CONFIDENCE = 70;

    private static final String CREATED_BY_REFLECTION = "REFLECTION";
    private static final String SOURCE_TYPE_REFLECTION = "REFLECTION";

    private static final DateTimeFormatter SOURCE_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AgentMapper agentMapper;
    private final AgentMemoryMapper agentMemoryMapper;
    private final AgentLogMapper agentLogMapper;
    private final AuthorResolver authorResolver;
    private final MemoryProperties memoryProperties;

    /**
     * Only ever used to invalidate one cache entry after a successful patch.
     *
     * The direction is safe: AgentProfileServiceImpl depends on mappers and on
     * SchemaCapabilities, never on this service, so there is no cycle to break with an
     * event or a @Lazy proxy.
     */
    private final AgentProfileService agentProfileService;

    public AgentMemoryServiceImpl(AgentMapper agentMapper,
                                 AgentMemoryMapper agentMemoryMapper,
                                 AgentLogMapper agentLogMapper,
                                 AuthorResolver authorResolver,
                                 MemoryProperties memoryProperties,
                                 AgentProfileService agentProfileService) {
        this.agentMapper = agentMapper;
        this.agentMemoryMapper = agentMemoryMapper;
        this.agentLogMapper = agentLogMapper;
        this.authorResolver = authorResolver;
        this.memoryProperties = memoryProperties;
        this.agentProfileService = agentProfileService;
    }

    // ========== Owner-facing read ==========

    @Override
    public Page<AgentMemoryResponse> getMemories(Long ownerId, Long agentId, Integer status,
                                                 String memoryType, int page, int size) {
        validateAgentOwnership(ownerId, agentId);

        LambdaQueryWrapper<AgentMemory> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AgentMemory::getAgentId, agentId);

        if (status != null) {
            // Reject an unknown status instead of returning an empty page that looks
            // like "this agent has no memories".
            if (!isKnownStatus(status)) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER);
            }
            queryWrapper.eq(AgentMemory::getStatus, status);
        }

        if (memoryType != null && !memoryType.isBlank()) {
            MemoryType type = MemoryType.fromCode(memoryType.trim());
            if (type == null) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER);
            }
            queryWrapper.eq(AgentMemory::getMemoryType, type.getCode());
        }

        queryWrapper.orderByDesc(AgentMemory::getCreatedAt).orderByDesc(AgentMemory::getId);

        Page<AgentMemory> memoryPage = agentMemoryMapper.selectPage(
                new Page<>(clampPage(page), clampSize(size)), queryWrapper);

        Page<AgentMemoryResponse> responsePage =
                new Page<>(memoryPage.getCurrent(), memoryPage.getSize(), memoryPage.getTotal());
        responsePage.setRecords(memoryPage.getRecords().stream()
                .map(this::buildResponse)
                .collect(Collectors.toList()));
        return responsePage;
    }

    // ========== Owner-facing brake ==========

    @Override
    @Transactional
    public AgentMemoryResponse updateMemory(Long ownerId, Long agentId, Long memoryId,
                                            AgentMemoryUpdateRequest request) {
        validateAgentOwnership(ownerId, agentId);

        boolean wantsStatusChange = request != null && request.getStatus() != null;
        boolean wantsContentChange = request != null
                && request.getContent() != null
                && !request.getContent().isBlank();
        boolean wantsScopeChange = request != null && request.getIsPublic() != null;
        if (!wantsStatusChange && !wantsContentChange && !wantsScopeChange) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }

        AgentMemory memory = agentMemoryMapper.selectById(memoryId);
        // The memory must belong to the agent in the path: without this check an owner
        // of agent A could patch agent B's memory by guessing an id.
        if (memory == null || !agentId.equals(memory.getAgentId())) {
            throw new BusinessException(ErrorCode.AGENT_MEMORY_NOT_FOUND);
        }

        Integer newStatus = null;
        if (wantsStatusChange) {
            if (!MemoryStatus.isOwnerAssignable(request.getStatus())) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER);
            }
            // DEPRECATED is terminal: a card the system retired must not be revived.
            // Checked here for a precise error message, and again in the UPDATE's WHERE
            // clause for the case where the retirement lands after this read.
            if (memory.isDeprecated()) {
                throw new BusinessException(ErrorCode.AGENT_MEMORY_DEPRECATED);
            }
            newStatus = request.getStatus();
        }

        String newScope = null;
        if (wantsScopeChange) {
            newScope = resolveScope(request.getIsPublic(), memory);
        }

        String newContent = null;
        Integer newVersion = null;
        if (wantsContentChange) {
            // An owner correction is untrusted input too, and it will be injected into
            // the decision prompt from phase 2 on.
            newContent = MemoryTextSanitizer.truncate(
                    MemoryTextSanitizer.sanitize(request.getContent()), USER_CONTENT_MAX_LENGTH);
            if (newContent.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER);
            }
            newVersion = memory.getVersion() == null ? null : memory.getVersion() + 1;
        }

        // Conditional write: the row must still be at the version we read, and a status
        // write additionally requires the status we read (so two opposite toggles cannot
        // both win) and a non-retired row. See applyOwnerEdit.
        int updated = agentMemoryMapper.applyOwnerEdit(memoryId, newStatus, newContent,
                newVersion, CREATED_BY_USER_EDIT, memory.getVersion(), memory.getStatus(),
                newScope);
        if (updated == 0) {
            log.info("Agent memory patch conflicted: agentId={}, memoryId={}, expectedVersion={}",
                    agentId, memoryId, memory.getVersion());
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }

        log.info("Agent memory updated: agentId={}, memoryId={}, ownerId={}, status={}, "
                        + "contentEdited={}, scope={}",
                agentId, memoryId, ownerId, newStatus, wantsContentChange, newScope);

        // The public profile is memoised for half a minute, and every field of this
        // patch can change it: scope decides whether the card is listed at all, status
        // hides and restores a published card, and content is the text on the page.
        // Evicting on any successful patch rather than only on the two that obviously
        // matter costs one rebuilt profile and removes a class of "I pressed the button
        // and nothing happened" from the owner panel.
        evictPublicProfile(agentId);

        // Report the row as it now stands rather than the pre-image plus our edit: a
        // concurrent retirement of a card we only corrected the text of must show up.
        AgentMemory refreshed = agentMemoryMapper.selectById(memoryId);
        if (refreshed != null) {
            return buildResponse(refreshed);
        }
        applyEditToLocalCopy(memory, newStatus, newContent, newVersion, newScope);
        return buildResponse(memory);
    }

    private void applyEditToLocalCopy(AgentMemory memory, Integer newStatus,
                                      String newContent, Integer newVersion,
                                      String newScope) {
        if (newStatus != null) {
            memory.setStatus(newStatus);
        }
        if (newContent != null) {
            memory.setContent(newContent);
            memory.setVersion(newVersion);
            memory.setCreatedBy(CREATED_BY_USER_EDIT);
        }
        if (newScope != null) {
            memory.setScope(newScope);
        }
    }

    /**
     * Translate the request's is_public into a scope, rejecting the cards that must not
     * be published.
     *
     * Publishing is the only direction that is restricted:
     * - a PERSONA_FACT is written straight from an executed action and quotes text the
     *   owner never reviewed, so it is refused with INVALID_PARAMETER and a message
     *   naming the rule, not with a silent no-op;
     * - a DEPRECATED card is one the platform stopped trusting, and putting it on a
     *   public page would be exactly the resurrection the terminal state exists to
     *   prevent. Reported as AGENT_MEMORY_DEPRECATED, the same code a status edit on a
     *   retired card already returns.
     *
     * Withdrawal is accepted on any card: it only ever reduces what is visible, and an
     * owner must be able to take a card down even after the system retired it.
     */
    private String resolveScope(Boolean isPublic, AgentMemory memory) {
        if (!Boolean.TRUE.equals(isPublic)) {
            return SCOPE_SELF;
        }
        if (!MemoryType.PERSONA_TRAIT.getCode().equals(memory.getMemoryType())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "只有人格特质卡（PERSONA_TRAIT）可以公开");
        }
        if (memory.isDeprecated()) {
            throw new BusinessException(ErrorCode.AGENT_MEMORY_DEPRECATED);
        }
        return SCOPE_PUBLIC;
    }

    /**
     * Never lets an eviction failure fail the patch that already committed.
     */
    private void evictPublicProfile(Long agentId) {
        try {
            agentProfileService.evict(agentId);
        } catch (Exception e) {
            log.warn("Could not evict the cached public profile of agent {}: {}",
                    agentId, e.getMessage());
        }
    }

    // ========== Injection (read path for the decision loop) ==========

    /**
     * The SQL already filters and orders; this method filters and orders again in Java.
     *
     * That is not redundancy for its own sake: "a disabled, retired or expired memory
     * never reaches the model" is the guarantee the whole owner-facing brake rests on,
     * and it is worth one predicate that a future query rewrite cannot silently void.
     * The comparator is also the documentation of the injection priority.
     */
    @Override
    public List<AgentMemoryCard> selectForInjection(Long agentId) {
        if (agentId == null) {
            return List.of();
        }
        int limit = Math.max(memoryProperties.getInjectLimit(), 0);
        if (limit == 0) {
            return List.of();
        }

        List<AgentMemory> rows = agentMemoryMapper.findInjectable(agentId, limit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        LocalDateTime now = LocalDateTime.now();
        return rows.stream()
                .filter(Objects::nonNull)
                .filter(memory -> isInjectable(memory, now))
                .sorted(injectionOrder())
                .limit(limit)
                .map(this::toCard)
                .collect(Collectors.toList());
    }

    private boolean isInjectable(AgentMemory memory, LocalDateTime now) {
        boolean active = memory.getStatus() != null
                && memory.getStatus() == MemoryStatus.ACTIVE.getCode();
        if (!active) {
            log.warn("Skipping non-active memory offered for injection: memoryId={}, status={}",
                    memory.getId(), memory.getStatus());
            return false;
        }
        if (memory.getExpiresAt() != null && !memory.getExpiresAt().isAfter(now)) {
            log.debug("Skipping expired memory: memoryId={}, expiresAt={}",
                    memory.getId(), memory.getExpiresAt());
            return false;
        }
        return true;
    }

    /**
     * Traits before facts, then importance, then recency. A distilled trait says more
     * about who the agent is than any single thing it did.
     */
    private Comparator<AgentMemory> injectionOrder() {
        Comparator<AgentMemory> byType = Comparator.comparingInt(this::typeRank);
        Comparator<AgentMemory> byImportance =
                Comparator.comparingInt((AgentMemory memory) -> nullSafeScore(memory.getImportanceScore()))
                        .reversed();
        Comparator<AgentMemory> byRecency =
                Comparator.comparing(AgentMemory::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()));
        return byType.thenComparing(byImportance).thenComparing(byRecency);
    }

    private int typeRank(AgentMemory memory) {
        return MemoryType.PERSONA_TRAIT.getCode().equals(memory.getMemoryType()) ? 0 : 1;
    }

    private int nullSafeScore(Integer score) {
        return score != null ? score : 0;
    }

    private AgentMemoryCard toCard(AgentMemory memory) {
        return AgentMemoryCard.builder()
                .memoryType(memory.getMemoryType())
                .content(MemoryTextSanitizer.truncate(
                        MemoryTextSanitizer.flatten(memory.getContent()), CONTENT_MAX_LENGTH))
                .confidenceScore(memory.getConfidenceScore())
                .source(sourceLabel(memory))
                .build();
    }

    /**
     * Human-readable provenance, e.g. "POST#123" or "REFLECTION 2026-07-26".
     */
    private String sourceLabel(AgentMemory memory) {
        if (CREATED_BY_REFLECTION.equals(memory.getCreatedBy())) {
            String date = memory.getCreatedAt() == null
                    ? null
                    : memory.getCreatedAt().format(SOURCE_DATE_FORMATTER);
            return date == null ? CREATED_BY_REFLECTION : CREATED_BY_REFLECTION + " " + date;
        }
        if (memory.getSourceType() != null && memory.getSourceId() != null) {
            return memory.getSourceType() + "#" + memory.getSourceId();
        }
        return memory.getCreatedBy() != null ? memory.getCreatedBy() : "UNKNOWN";
    }

    // ========== Reflection input ==========

    /**
     * Reflection is an injection path like any other, so it applies exactly the same
     * two gates as {@link #selectForInjection}: only ACTIVE, unexpired memories may be
     * shown to the model, and reflection's own audit rows are not behaviour.
     *
     * The alternative - letting a disabled fact into the behaviour pack - would launder
     * the owner brake: the model would distil the disabled memory into a brand new
     * ACTIVE trait and the "deleted" content would be back in the persona.
     */
    @Override
    public ReflectionContext buildReflectionContext(Long agentId, LocalDateTime since) {
        int behaviorLimit = Math.max(memoryProperties.getBehaviorLimit(), 0);
        int traitLimit = Math.max(memoryProperties.getTraitLimit(), 0);
        LocalDateTime now = LocalDateTime.now();

        List<String> behaviors = new ArrayList<>();

        List<AgentMemory> facts = agentMemoryMapper.findByTypeSince(
                agentId, MemoryType.PERSONA_FACT.getCode(), since, behaviorLimit);
        if (facts != null) {
            for (AgentMemory fact : facts) {
                if (fact == null || !isInjectable(fact, now)) {
                    continue;
                }
                addBehaviorLine(behaviors, "记忆", fact.getContent(), behaviorLimit);
            }
        }

        List<AgentLog> logs = agentLogMapper.findByAgentIdSince(agentId, since, behaviorLimit);
        if (logs != null) {
            for (AgentLog entry : logs) {
                if (entry == null || isReflectionAudit(entry)) {
                    continue;
                }
                addBehaviorLine(behaviors,
                        "动作" + safeLabel(entry.getActionType()) + "/" + safeLabel(entry.getActionResult()),
                        entry.getActionContent(), behaviorLimit);
            }
        }

        List<AgentMemory> traits = agentMemoryMapper.findActiveByType(
                agentId, MemoryType.PERSONA_TRAIT.getCode(), traitLimit);
        List<ReflectionContext.TraitSnapshot> snapshots = traits == null ? List.of() : traits.stream()
                .filter(trait -> trait != null && isInjectable(trait, now))
                .map(trait -> ReflectionContext.TraitSnapshot.builder()
                        .id(trait.getId())
                        .content(MemoryTextSanitizer.truncate(
                                MemoryTextSanitizer.flatten(trait.getContent()), CONTENT_MAX_LENGTH))
                        .importanceScore(trait.getImportanceScore())
                        .confidenceScore(trait.getConfidenceScore())
                        .build())
                .collect(Collectors.toList());

        return ReflectionContext.builder()
                .recentBehaviors(behaviors)
                .existingTraits(snapshots)
                .maxNewTraits(memoryProperties.getMaxNewTraits())
                .maxTotalTraits(memoryProperties.getTraitLimit())
                .build();
    }

    private void addBehaviorLine(List<String> target, String prefix, String body, int limit) {
        if (target.size() >= limit) {
            return;
        }
        String clean = MemoryTextSanitizer.sanitize(body);
        if (clean.isEmpty()) {
            return;
        }
        target.add("[" + prefix + "] " + MemoryTextSanitizer.truncate(clean, BEHAVIOR_LINE_MAX_LENGTH));
    }

    private String safeLabel(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return MemoryTextSanitizer.truncate(MemoryTextSanitizer.sanitize(value), 30);
    }

    /**
     * Rows written by the reflection job's own billing step. The mapper filters them
     * too; this is the same defence-in-depth as the injection filter, and it is what
     * keeps the job from distilling its own bookkeeping.
     */
    private boolean isReflectionAudit(AgentLog entry) {
        return entry.getActionResult() != null
                && entry.getActionResult().startsWith(CREATED_BY_REFLECTION);
    }

    // ========== Reflection output ==========

    /**
     * Every value here came out of a language model, so the order of operations is
     * defensive: verify ownership of the ids first, then write, and only then let the
     * retention sweep look at the result.
     *
     * Transactional and free of network calls - the LLM call happened in the scheduler,
     * outside any transaction.
     */
    @Override
    @Transactional
    public int applyReflection(Agent agent, ReflectionResult result) {
        if (agent == null || agent.getId() == null || result == null || !result.isSuccessful()) {
            return 0;
        }
        Long agentId = agent.getId();

        // The agent's own traits. Anything the model references outside this set is a
        // hallucination (or an attempt to edit another agent's memory) and is dropped.
        Set<Long> ownTraitIds = new HashSet<>();
        List<Long> loaded = agentMemoryMapper.findTraitIds(agentId);
        if (loaded != null) {
            loaded.stream().filter(Objects::nonNull).forEach(ownTraitIds::add);
        }

        int changed = 0;
        changed += applyTraitRevisions(agentId, result.safeUpdatedTraits(), ownTraitIds);
        changed += applyTraitDeprecations(agentId, result.safeDeprecatedTraitIds(), ownTraitIds);
        changed += insertNewTraits(agent, result.safeNewTraits());

        enforceRetention(agentId, MemoryType.PERSONA_TRAIT, memoryProperties.getTraitLimit());

        log.info("Reflection applied: agentId={}, changedRows={}, new={}, updated={}, deprecated={}",
                agentId, changed, result.safeNewTraits().size(),
                result.safeUpdatedTraits().size(), result.safeDeprecatedTraitIds().size());
        return changed;
    }

    private int applyTraitRevisions(Long agentId, List<ReflectionResult.TraitUpdate> updates,
                                    Set<Long> ownTraitIds) {
        int changed = 0;
        for (ReflectionResult.TraitUpdate update : updates) {
            if (update == null || update.getId() == null) {
                log.warn("Reflection returned a trait update without an id: agentId={}", agentId);
                continue;
            }
            if (!ownTraitIds.contains(update.getId())) {
                log.warn("Reflection tried to update a trait that is not this agent's: "
                        + "agentId={}, traitId={}", agentId, update.getId());
                continue;
            }
            String content = sanitizedContent(update.getContent());
            if (content.isEmpty()) {
                log.warn("Reflection returned an empty trait update: agentId={}, traitId={}",
                        agentId, update.getId());
                continue;
            }
            // Evidence follows the content: supplied notes are sanitized and written,
            // an absent note clears the old one. Carrying the previous citation over to
            // rewritten text would make the trait look corroborated by something that no
            // longer says it - worse than having no citation at all.
            String evidence = update.getEvidence() == null
                    ? null
                    : MemoryTextSanitizer.truncate(
                            MemoryTextSanitizer.sanitize(update.getEvidence()), EVIDENCE_MAX_LENGTH);
            // The statement pins agent_id and memory_type as well, so a concurrent
            // change cannot turn this into a cross-agent write.
            int rows = agentMemoryMapper.applyTraitRevision(update.getId(), agentId, content, evidence,
                    clampScore(update.getImportanceScore(), DEFAULT_IMPORTANCE),
                    clampScore(update.getConfidenceScore(), DEFAULT_TRAIT_CONFIDENCE));
            if (rows == 0) {
                log.warn("Trait revision matched no row (retired or reassigned meanwhile): "
                        + "agentId={}, traitId={}", agentId, update.getId());
                continue;
            }
            changed += rows;
        }
        return changed;
    }

    private int applyTraitDeprecations(Long agentId, List<Long> ids, Set<Long> ownTraitIds) {
        Set<Long> accepted = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            if (!ownTraitIds.contains(id)) {
                log.warn("Reflection tried to deprecate a trait that is not this agent's: "
                        + "agentId={}, traitId={}", agentId, id);
                continue;
            }
            accepted.add(id);
        }
        if (accepted.isEmpty()) {
            return 0;
        }
        return agentMemoryMapper.deprecateTraitsForAgent(agentId, new ArrayList<>(accepted));
    }

    /**
     * Insert the accepted new traits.
     *
     * De-duplicated against the agent's live traits and within the batch on a normalized
     * form of the content. Reflection runs daily over an overlapping window, and a
     * manual re-trigger repeats it entirely, so without this a stable personality trait
     * would accumulate one identical copy per run and crowd the injection budget.
     */
    private int insertNewTraits(Agent agent, List<ReflectionResult.TraitDraft> drafts) {
        int budget = Math.max(memoryProperties.getMaxNewTraits(), 0);
        if (budget == 0) {
            return 0;
        }

        // The comparison set has to be exactly the traits that can still be injected.
        // A dedup set built from "ACTIVE" alone included expired cards, so a fresh trait
        // repeating an expired one was dropped as a duplicate while the expired card
        // could not be injected either - the content vanished from the persona entirely.
        LocalDateTime now = LocalDateTime.now();
        Set<String> seen = new HashSet<>();
        List<AgentMemory> existing = agentMemoryMapper.findActiveByType(agent.getId(),
                MemoryType.PERSONA_TRAIT.getCode(), Math.max(memoryProperties.getTraitLimit(), 1));
        if (existing != null) {
            existing.stream()
                    .filter(trait -> trait != null && isInjectable(trait, now))
                    .map(trait -> normalizeForDedup(trait.getContent()))
                    .filter(key -> !key.isEmpty())
                    .forEach(seen::add);
        }

        int inserted = 0;
        for (ReflectionResult.TraitDraft draft : drafts) {
            if (inserted >= budget) {
                log.warn("Reflection returned more traits than allowed, dropping the rest: "
                        + "agentId={}, allowed={}, returned={}", agent.getId(), budget, drafts.size());
                break;
            }
            if (draft == null) {
                continue;
            }
            String content = sanitizedContent(draft.getContent());
            if (content.isEmpty()) {
                log.warn("Reflection returned an empty trait: agentId={}", agent.getId());
                continue;
            }
            String dedupKey = normalizeForDedup(content);
            if (!seen.add(dedupKey)) {
                log.warn("Skipping duplicate reflection trait: agentId={}, content={}",
                        agent.getId(), MemoryTextSanitizer.truncate(content, 60));
                continue;
            }

            AgentMemory trait = new AgentMemory();
            trait.setAgentId(agent.getId());
            trait.setOwnerId(agent.getOwnerId());
            trait.setPageId(null);
            trait.setNamespace("agent:" + agent.getId());
            trait.setMemoryType(MemoryType.PERSONA_TRAIT.getCode());
            trait.setContent(content);
            trait.setEvidence(MemoryTextSanitizer.truncate(
                    MemoryTextSanitizer.sanitize(draft.getEvidence()), EVIDENCE_MAX_LENGTH));
            trait.setSourceType(SOURCE_TYPE_REFLECTION);
            trait.setSourceId(null);
            trait.setScope(SCOPE_SELF);
            trait.setImportanceScore(clampScore(draft.getImportanceScore(), DEFAULT_IMPORTANCE));
            trait.setConfidenceScore(clampScore(draft.getConfidenceScore(), DEFAULT_TRAIT_CONFIDENCE));
            trait.setStatus(MemoryStatus.ACTIVE.getCode());
            trait.setVersion(1);
            trait.setCreatedBy(CREATED_BY_REFLECTION);

            agentMemoryMapper.insert(trait);
            inserted++;
        }
        return inserted;
    }

    private String sanitizedContent(String raw) {
        return MemoryTextSanitizer.truncate(MemoryTextSanitizer.sanitize(raw), CONTENT_MAX_LENGTH);
    }

    /**
     * Comparison key for trait de-duplication: compatibility-folded (NFKC), whitespace
     * stripped, case folded.
     *
     * NFKC matters because the same claim written with full-width punctuation
     * ("支持Ａ，反对Ｂ") is otherwise a different string from its half-width twin and both
     * would occupy an injection slot. Punctuation is deliberately NOT stripped: "支持A，
     * 反对B" and "支持A、反对B" may well be different claims, and over-merging silently
     * loses a real trait.
     */
    private String normalizeForDedup(String content) {
        if (content == null) {
            return "";
        }
        return MemoryTextSanitizer.foldCompatibility(content)
                .replaceAll("\\s+", "")
                .toLowerCase();
    }

    /**
     * Scores are declared 0-100 in the schema; a model that answers 900 or -5 must not
     * be able to park a trait permanently at the top of the injection order.
     */
    private int clampScore(Integer value, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(0, Math.min(100, value));
    }

    // ========== Hot-path write ==========

    /**
     * Failure containment, in order:
     * - one bad card never stops the other cards of the same cycle;
     * - a failing retention sweep never undoes the cards just written;
     * - nothing propagates to the caller, because the caller is the agent loop and
     *   its action + token charge are already committed.
     *
     * Deliberately NOT transactional: each insert stands on its own, and the caller
     * must already be outside a transaction (see AgentActionExecutor's contract).
     */
    @Override
    public void recordActionMemories(Agent agent, List<AgentActionOutcome> outcomes) {
        if (agent == null || agent.getId() == null || outcomes == null || outcomes.isEmpty()) {
            return;
        }

        int written = 0;
        for (AgentActionOutcome outcome : outcomes) {
            if (outcome == null || !outcome.isSuccess()) {
                continue;
            }
            try {
                if (writeFactCard(agent, outcome)) {
                    written++;
                }
            } catch (Exception e) {
                log.warn("Failed to write agent memory card: agentId={}, action={}, error={}",
                        agent.getId(), outcome.getAction(), e.getMessage());
            }
        }

        if (written == 0) {
            return;
        }

        try {
            enforceRetention(agent.getId(), MemoryType.PERSONA_FACT,
                    memoryProperties.getPersonaFactLimit());
        } catch (Exception e) {
            log.warn("Agent memory retention sweep failed: agentId={}, error={}",
                    agent.getId(), e.getMessage());
        }
    }

    /**
     * @return true when a card was actually inserted
     */
    private boolean writeFactCard(Agent agent, AgentActionOutcome outcome) {
        String content = buildFactContent(outcome);
        if (content == null || content.isEmpty()) {
            return false;
        }

        AgentMemory memory = new AgentMemory();
        memory.setAgentId(agent.getId());
        memory.setOwnerId(agent.getOwnerId());
        memory.setPageId(null); // reserved for the future wiki page link
        memory.setNamespace("agent:" + agent.getId());
        memory.setMemoryType(MemoryType.PERSONA_FACT.getCode());
        memory.setContent(content);
        memory.setEvidence(buildEvidence(outcome));
        memory.setSourceType(outcome.getSourceType());
        memory.setSourceId(outcome.getSourceId());
        memory.setScope(SCOPE_SELF);
        memory.setImportanceScore(DEFAULT_IMPORTANCE);
        memory.setConfidenceScore(DEFAULT_CONFIDENCE);
        memory.setStatus(MemoryStatus.ACTIVE.getCode());
        memory.setVersion(1);
        memory.setCreatedBy(CREATED_BY_SYSTEM);

        agentMemoryMapper.insert(memory);

        log.debug("Agent memory card written: agentId={}, action={}, sourceType={}, sourceId={}",
                agent.getId(), outcome.getAction(), outcome.getSourceType(), outcome.getSourceId());
        return true;
    }

    /**
     * The five phase-1 templates. Anything else (IGNORE, unknown action) produces no
     * card - an agent that did nothing has nothing to remember.
     *
     * Every interpolated fragment is sanitized on its own and the assembled body is
     * sanitized again, so a secret cannot survive by being split across fragments.
     */
    private String buildFactContent(AgentActionOutcome outcome) {
        if (outcome.getAction() == null) {
            return null;
        }

        String body;
        switch (outcome.getAction()) {
            case POST:
                body = String.format("我发布了帖子《%s》，观点摘要：%s",
                        headline(outcome.getSelfContent()),
                        segment(outcome.getSelfContent()));
                break;
            case REPLY:
                body = String.format("我回复了 Post#%s（作者 %s），我的观点：%s",
                        postRef(outcome), authorLabel(outcome), segment(outcome.getSelfContent()));
                break;
            case LIKE:
                body = String.format("我点赞了 Post#%s（作者 %s），认同其观点：%s",
                        postRef(outcome), authorLabel(outcome), segment(outcome.getTargetSummary()));
                break;
            case DISLIKE:
                body = String.format("我点踩了 Post#%s（作者 %s），不认同其观点：%s",
                        postRef(outcome), authorLabel(outcome), segment(outcome.getTargetSummary()));
                break;
            case CREATE_BOUNTY:
                body = String.format("我创建了悬赏《%s》，需求摘要：%s",
                        headline(outcome.getSelfContent()), segment(outcome.getTargetSummary()));
                break;
            default:
                return null;
        }

        // redactAndFlatten, not sanitize: the fragments above were already normalized,
        // and NFKC here would rewrite the template's own 《》（） to ASCII.
        return MemoryTextSanitizer.truncate(
                MemoryTextSanitizer.redactAndFlatten(body), CONTENT_MAX_LENGTH);
    }

    /**
     * Evidence holds ids only - never user text - so it needs no sanitizing and stays
     * usable as a jump target in the UI.
     */
    private String buildEvidence(AgentActionOutcome outcome) {
        StringBuilder evidence = new StringBuilder();
        if (outcome.getSourceType() != null && outcome.getSourceId() != null) {
            evidence.append(outcome.getSourceType()).append('#').append(outcome.getSourceId());
        }
        if (outcome.getTargetPostId() != null) {
            if (evidence.length() > 0) {
                evidence.append(' ');
            }
            evidence.append("target=POST#").append(outcome.getTargetPostId());
        }
        return evidence.length() == 0 ? null : evidence.toString();
    }

    private String postRef(AgentActionOutcome outcome) {
        return outcome.getTargetPostId() == null ? "?" : String.valueOf(outcome.getTargetPostId());
    }

    /**
     * Display name of the target author, resolved here rather than in the executor so
     * the lookup happens outside the action transaction. Any failure degrades to a
     * "Type#id" label instead of losing the card.
     */
    private String authorLabel(AgentActionOutcome outcome) {
        Long authorId = outcome.getTargetAuthorId();
        if (authorId == null) {
            return "未知";
        }
        try {
            AuthorResolver.AuthorInfo info =
                    authorResolver.resolve(outcome.getTargetAuthorType(), authorId);
            if (info != null && info.getAuthorName() != null && !info.getAuthorName().isBlank()) {
                return MemoryTextSanitizer.truncate(
                        MemoryTextSanitizer.sanitize(info.getAuthorName()), AUTHOR_LABEL_MAX_LENGTH);
            }
        } catch (Exception e) {
            log.debug("Author name lookup failed for memory card: type={}, id={}, error={}",
                    outcome.getTargetAuthorType(), authorId, e.getMessage());
        }
        String type = outcome.getTargetAuthorType() == null ? "Author" : outcome.getTargetAuthorType();
        return MemoryTextSanitizer.sanitize(type) + "#" + authorId;
    }

    /**
     * First line / first sentence of a body, for the quoted headline.
     */
    private String headline(String text) {
        String clean = MemoryTextSanitizer.sanitize(text);
        if (clean.isEmpty()) {
            return "无标题";
        }
        int cut = clean.length();
        for (String delimiter : new String[]{"。", "！", "？", "!", "?", ".", "；", ";"}) {
            int index = clean.indexOf(delimiter);
            if (index > 0 && index < cut) {
                cut = index;
            }
        }
        return MemoryTextSanitizer.truncate(clean.substring(0, cut), HEADLINE_MAX_LENGTH);
    }

    private String segment(String text) {
        String clean = MemoryTextSanitizer.sanitize(text);
        return clean.isEmpty() ? "（无内容）" : MemoryTextSanitizer.truncate(clean, SEGMENT_MAX_LENGTH);
    }

    /**
     * Retention: keep at most {@code limit} non-deprecated cards of one type per agent,
     * retiring the least important and oldest excess. Runs inline on the write paths
     * (two cheap indexed queries) instead of needing a scheduler of its own.
     *
     * Same mechanism for both types: PERSONA_FACT is capped as actions accumulate,
     * PERSONA_TRAIT as reflections accumulate.
     */
    private void enforceRetention(Long agentId, MemoryType memoryType, int limit) {
        if (limit <= 0) {
            return;
        }
        String type = memoryType.getCode();
        int live = agentMemoryMapper.countLiveByType(agentId, type);
        int excess = live - limit;
        if (excess <= 0) {
            return;
        }

        List<Long> victims = agentMemoryMapper.findRetentionCandidates(agentId, type, excess);
        if (victims == null || victims.isEmpty()) {
            return;
        }
        int deprecated = agentMemoryMapper.deprecateByIds(victims);
        log.info("Agent memory retention applied: agentId={}, type={}, live={}, limit={}, deprecated={}",
                agentId, type, live, limit, deprecated);
    }

    // ========== Helper Methods ==========

    /**
     * Same semantics as AgentServiceImpl.validateAgentOwnership: 404 for a missing
     * agent, 403 for someone else's agent.
     */
    private Agent validateAgentOwnership(Long ownerId, Long agentId) {
        Agent agent = agentMapper.selectById(agentId);

        if (agent == null) {
            throw new BusinessException(ErrorCode.AGENT_NOT_FOUND);
        }

        if (!agent.getOwnerId().equals(ownerId)) {
            throw new BusinessException(ErrorCode.AGENT_NOT_OWNER);
        }

        return agent;
    }

    /**
     * Page size bounds.
     *
     * Math.min(size, 50) alone was not enough: MyBatis Plus treats a non-positive size
     * as "do not paginate", so size=-1 returned every memory of the agent in one
     * response. Illegal values fall back to the endpoint's own default instead of a
     * 400, matching how the existing list endpoints clamp silently.
     */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private int clampPage(int page) {
        return Math.max(page, 1);
    }

    private int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private boolean isKnownStatus(Integer status) {
        try {
            MemoryStatus.fromCode(status);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private AgentMemoryResponse buildResponse(AgentMemory memory) {
        MemoryType type = MemoryType.fromCode(memory.getMemoryType());
        String statusText;
        try {
            statusText = MemoryStatus.fromCode(memory.getStatus()).getText();
        } catch (IllegalArgumentException e) {
            statusText = null;
        }

        return AgentMemoryResponse.builder()
                .id(memory.getId())
                .agentId(memory.getAgentId())
                .memoryType(memory.getMemoryType())
                .memoryTypeText(type != null ? type.getText() : null)
                .content(memory.getContent())
                .evidence(memory.getEvidence())
                .sourceType(memory.getSourceType())
                .sourceId(memory.getSourceId())
                .scope(memory.getScope())
                .isPublic(SCOPE_PUBLIC.equals(memory.getScope()))
                .importanceScore(memory.getImportanceScore())
                .confidenceScore(memory.getConfidenceScore())
                .status(memory.getStatus())
                .statusText(statusText)
                .version(memory.getVersion())
                .createdBy(memory.getCreatedBy())
                .expiresAt(formatDateTime(memory.getExpiresAt()))
                .createdAt(formatDateTime(memory.getCreatedAt()))
                .updatedAt(formatDateTime(memory.getUpdatedAt()))
                .build();
    }

    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(DATE_FORMATTER);
    }
}
