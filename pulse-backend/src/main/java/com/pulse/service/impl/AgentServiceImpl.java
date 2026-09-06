package com.pulse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.dto.request.AgentCreateRequest;
import com.pulse.dto.request.AgentDeleteRequest;
import com.pulse.dto.request.AgentReviveRequest;
import com.pulse.dto.request.AgentUpdateRequest;
import com.pulse.dto.response.AgentDetailResponse;
import com.pulse.dto.response.AgentListItemResponse;
import com.pulse.dto.response.AgentLogResponse;
import com.pulse.dto.response.AgentReviveResponse;
import com.pulse.entity.Agent;
import com.pulse.entity.AgentLog;
import com.pulse.entity.User;
import com.pulse.enums.AgentStatus;
import com.pulse.enums.ProviderMode;
import com.pulse.enums.ActionType;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.AgentLogMapper;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.PostMapper;
import com.pulse.mapper.UserMapper;
import com.pulse.entity.Post;
import com.pulse.service.AgentService;
import com.pulse.service.support.LlmCredentialResolver;
import com.pulse.config.AgentTemplateCatalog;
import com.pulse.config.PlatformLlmProperties;
import com.pulse.config.SchemaCapabilities;
import com.pulse.dto.AgentProviderSettings;
import com.pulse.dto.AgentWakeSettings;
import com.pulse.service.support.WakeScheduleCalculator;
import com.pulse.util.AesUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent Service Implementation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private final AgentMapper agentMapper;
    private final AgentLogMapper agentLogMapper;
    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final AesUtil aesUtil;
    private final SchemaCapabilities schemaCapabilities;
    private final WakeScheduleCalculator wakeScheduleCalculator;
    private final LlmCredentialResolver credentialResolver;
    private final PlatformLlmProperties platformLlmProperties;
    private final AgentTemplateCatalog agentTemplateCatalog;

    /**
     * Rhythm wake-ups per day used when seeding a new agent's schedule; the scheduler
     * owns the same knob for recomputation.
     */
    @Value("${scheduler.agent-loop.target-daily-rhythm-wakes:3}")
    private int targetDailyRhythmWakes;

    @Value("${scheduler.agent-loop.default-daily-wake-budget:4}")
    private int defaultDailyWakeBudget;

    /**
     * What api_key_masked says for an agent running on the platform's key. A sentinel,
     * not a mask of anything: there is no per-agent key to mask.
     */
    private static final String PLATFORM_API_KEY_PLACEHOLDER = "PLATFORM";

    /** Daytime window used when a stored bound is missing or unusable. */
    private static final int DEFAULT_WAKE_HOURS_START = 9;
    private static final int DEFAULT_WAKE_HOURS_END = 23;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    /**
     * Local ISO-8601 without a zone suffix, for the wake-rhythm timestamps.
     *
     * Deliberately NOT the formatter above. That one appends a literal 'Z', which claims
     * the value is UTC when it is in fact server-local - harmless for a "created 3 days
     * ago" label, but next_wake_at is a clock time the owner reads as "my agent wakes at
     * 21:40", and being eight hours out makes the whole rhythm feature look broken. New
     * fields therefore state local time honestly, matching how the bounty responses ship
     * their LocalDateTime values.
     */
    private static final DateTimeFormatter LOCAL_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Override
    @Transactional
    public AgentDetailResponse createAgent(Long ownerId, AgentCreateRequest request) {
        String name = normalizeText(request.getName());
        String baseUrl = normalizeText(request.getBaseUrl());
        String apiKey = normalizeText(request.getApiKey());
        String modelName = normalizeText(request.getModelName());
        String systemPrompt = normalizeText(request.getSystemPrompt());

        // Which credentials this agent will run on decides which of the three fields
        // above are required, so it is resolved before any of them is looked at.
        ProviderMode providerMode = resolveRequestedMode(request.getProviderMode());
        String templateId = resolveTemplateId(request.getTemplateId());

        if (providerMode == ProviderMode.PLATFORM) {
            if (!credentialResolver.isPlatformAvailable()) {
                // Not 500 and not a silent downgrade to BYOK: the request is well formed,
                // this deployment simply does not offer a hosted model. A downgrade would
                // create an agent with no key that fails on its first wake-up.
                throw new BusinessException(ErrorCode.PLATFORM_MODEL_UNAVAILABLE);
            }
            // Stored as null rather than as a copy of the platform settings. A copy would
            // be a second source of truth for a value the operator can change, and rotating
            // the platform key would then leave every agent row holding a stale one.
            baseUrl = null;
            apiKey = null;
            modelName = null;
        } else {
            // The @NotBlank annotations these replace could not stay on the DTO: whether
            // the field is required depends on provider_mode. Same error code, so a BYOK
            // client sees exactly the response it saw before.
            requirePresent(baseUrl, apiKey, modelName);
        }

        // Check if name already exists for this owner
        if (agentNameExists(ownerId, name)) {
            throw new BusinessException(ErrorCode.AGENT_NAME_EXISTS);
        }

        // Create agent with encrypted API key
        Agent agent = new Agent();
        agent.setOwnerId(ownerId);
        agent.setName(name);
        agent.setAvatarUrl(request.getAvatarUrl());
        // Trim baseUrl to avoid URL encoding issues (spaces become %20)
        agent.setBaseUrl(baseUrl);
        agent.setApiKey(apiKey != null ? aesUtil.encrypt(apiKey) : null); // Encrypt API Key
        agent.setModelName(modelName);
        agent.setSystemPrompt(systemPrompt);
        agent.setTokenThreshold(request.getTokenThreshold());
        agent.setUsedTokens(0L);
        agent.setStatus(AgentStatus.ALIVE.getCode());
        agent.setIsUnlimited(request.getIsUnlimited());
        agent.setVersion(0);

        agentMapper.insert(agent);
        persistProviderMode(agent, providerMode, templateId);
        assignInitialWakeRhythm(agent);
        applyRequestedWakeRhythm(agent, request);

        log.info("Agent created: agentId={}, ownerId={}, name={}, providerMode={}",
                agent.getId(), ownerId, agent.getName(), providerMode);

        return buildDetailResponse(agent, ownerId);
    }

    /**
     * Parse the requested mode, defaulting to BYOK.
     *
     * An unrecognised value is rejected rather than defaulted: a client that meant
     * PLATFORM and misspelled it would otherwise get a BYOK agent it never asked for, and
     * find out at the first wake-up.
     */
    private ProviderMode resolveRequestedMode(String requested) {
        if (requested == null || requested.isBlank()) {
            return ProviderMode.BYOK;
        }
        ProviderMode mode = ProviderMode.fromCode(requested);
        if (mode == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        return mode;
    }

    /**
     * Accept a template id only if it names a template that actually ships.
     *
     * Stored ids are meant to answer "how many people picked the philosopher", so an
     * arbitrary string would make the column useless the first time a client sent one.
     * A blank value stays null: writing a hand-written prompt is the normal case, not an
     * error.
     */
    private String resolveTemplateId(String requested) {
        String templateId = normalizeText(requested);
        if (templateId == null || templateId.isEmpty()) {
            return null;
        }
        if (!agentTemplateCatalog.exists(templateId)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        return templateId;
    }

    private void requirePresent(String baseUrl, String apiKey, String modelName) {
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(modelName)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Write provider_mode / template_id, in a separate statement after the insert.
     *
     * Same pattern and the same reason as {@link #assignInitialWakeRhythm}: the columns
     * are outside the generated INSERT, so a database without the migration can still
     * create agents. The difference is what a failure means. A missing rhythm is
     * cosmetic and only warned about; a PLATFORM agent whose mode was not stored is a
     * broken agent - it would read back as BYOK, with no key - so that failure rolls the
     * creation back rather than handing the owner something that cannot work.
     */
    private void persistProviderMode(Agent agent, ProviderMode mode, String templateId) {
        if (!schemaCapabilities.isAgentProviderModeColumns()) {
            if (mode == ProviderMode.PLATFORM) {
                // Unreachable in practice - isPlatformAvailable() already covers it - but
                // stated here so the invariant does not depend on that call staying put.
                throw new BusinessException(ErrorCode.PLATFORM_MODEL_UNAVAILABLE);
            }
            return;
        }
        try {
            agentMapper.updateProviderMode(agent.getId(), mode.getCode(), templateId);
        } catch (Exception e) {
            log.error("Could not store the provider mode for agent {}: {}", agent.getId(), e.getMessage());
            if (mode == ProviderMode.PLATFORM) {
                throw new BusinessException(ErrorCode.PLATFORM_MODEL_UNAVAILABLE);
            }
        }
        agent.setProviderMode(mode.getCode());
        agent.setTemplateId(templateId);
    }

    @Override
    public Page<AgentListItemResponse> getAgentList(Long ownerId, Integer status, int page, int size) {
        Page<Agent> pageParam = new Page<>(page, Math.min(size, 50));

        LambdaQueryWrapper<Agent> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Agent::getOwnerId, ownerId);

        if (status != null) {
            queryWrapper.eq(Agent::getStatus, status);
        }

        queryWrapper.orderByDesc(Agent::getCreatedAt);

        Page<Agent> agentPage = agentMapper.selectPage(pageParam, queryWrapper);

        // Convert to response
        Page<AgentListItemResponse> responsePage = new Page<>(agentPage.getCurrent(), agentPage.getSize(), agentPage.getTotal());
        Map<Long, AgentWakeSettings> wakeSettings = loadWakeSettings(agentPage.getRecords());
        Map<Long, AgentProviderSettings> providerSettings = loadProviderSettings(agentPage.getRecords());
        List<AgentListItemResponse> responses = agentPage.getRecords().stream()
                .map(agent -> buildListItemResponse(agent, wakeSettings.get(agent.getId()),
                        providerSettings.get(agent.getId())))
                .collect(Collectors.toList());
        responsePage.setRecords(responses);

        return responsePage;
    }

    @Override
    public AgentDetailResponse getAgentDetail(Long ownerId, Long agentId) {
        Agent agent = validateAgentOwnership(ownerId, agentId);
        return buildDetailResponse(agent, ownerId);
    }

    @Override
    @Transactional
    public AgentDetailResponse updateAgent(Long ownerId, Long agentId, AgentUpdateRequest request) {
        Agent agent = validateAgentOwnership(ownerId, agentId);

        // A PLATFORM agent has no credentials of its own, so accepting any of the three
        // would store a value nothing will ever read - and leave the owner believing they
        // had switched their agent onto their own key. Refusing says what happened.
        // provider_mode itself is not a field on the update request at all, so there is
        // nothing to ignore here: see AgentUpdateRequest.
        if (isPlatformAgent(agent)
                && (request.getBaseUrl() != null || request.getApiKey() != null
                    || request.getModelName() != null)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }

        // Update fields if provided
        if (request.getName() != null) {
            String normalizedName = normalizeText(request.getName());
            if (!normalizedName.equals(agent.getName()) && agentNameExists(ownerId, normalizedName)) {
                throw new BusinessException(ErrorCode.AGENT_NAME_EXISTS);
            }
            agent.setName(normalizedName);
        }

        if (request.getAvatarUrl() != null) {
            agent.setAvatarUrl(request.getAvatarUrl());
        }

        if (request.getBaseUrl() != null) {
            // Trim baseUrl to avoid URL encoding issues
            agent.setBaseUrl(normalizeText(request.getBaseUrl()));
        }

        if (request.getApiKey() != null) {
            agent.setApiKey(aesUtil.encrypt(normalizeText(request.getApiKey()))); // Encrypt new API Key
        }

        if (request.getModelName() != null) {
            agent.setModelName(normalizeText(request.getModelName()));
        }

        if (request.getSystemPrompt() != null) {
            agent.setSystemPrompt(normalizeText(request.getSystemPrompt()));
        }

        if (request.getTokenThreshold() != null) {
            agent.setTokenThreshold(request.getTokenThreshold());
        }

        if (request.getIsUnlimited() != null) {
            agent.setIsUnlimited(request.getIsUnlimited());
        }

        agentMapper.updateById(agent);

        // Separate, capability-guarded statement: the rhythm columns are invisible to
        // updateById on purpose (see Agent), so an un-migrated database keeps working.
        applyWakeSettings(agent, request);

        log.info("Agent updated: agentId={}, ownerId={}", agentId, ownerId);

        return buildDetailResponse(agent, ownerId);
    }

    @Override
    @Transactional
    public AgentReviveResponse reviveAgent(Long ownerId, Long agentId, AgentReviveRequest request) {
        Agent agent = validateAgentOwnership(ownerId, agentId);

        // Reset tokens and status
        Long newThreshold = request.getNewThreshold() != null ? request.getNewThreshold() : agent.getTokenThreshold();
        agentMapper.resetAgent(agentId, newThreshold);

        log.info("Agent revived: agentId={}, ownerId={}, newThreshold={}", agentId, ownerId, newThreshold);

        return AgentReviveResponse.builder()
                .id(agentId)
                .status(AgentStatus.ALIVE.getCode())
                .usedTokens(0L)
                .tokenThreshold(newThreshold)
                .revivedAt(formatDateTime(LocalDateTime.now()))
                .build();
    }

    @Override
    @Transactional
    public void deleteAgent(Long ownerId, Long agentId, AgentDeleteRequest request) {
        Agent agent = validateAgentOwnership(ownerId, agentId);

        // Verify confirmation name matches
        if (!agent.getName().equals(request.getConfirmName())) {
            throw new BusinessException(ErrorCode.AGENT_CONFIRM_NAME_MISMATCH);
        }

        agentMapper.deleteById(agentId);

        log.info("Agent deleted: agentId={}, ownerId={}", agentId, ownerId);
    }

    @Override
    public boolean agentNameExists(Long ownerId, String name) {
        LambdaQueryWrapper<Agent> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Agent::getOwnerId, ownerId);
        queryWrapper.eq(Agent::getName, name);
        return agentMapper.selectCount(queryWrapper) > 0;
    }

    @Override
    public List<AgentLogResponse> getAgentLogs(Long ownerId, Long agentId, int limit) {
        // Validate ownership first
        validateAgentOwnership(ownerId, agentId);

        List<AgentLog> logs = agentLogMapper.findByAgentId(agentId, Math.min(limit, 50));
        Map<Long, Post> targetPosts = loadTargetPosts(logs);

        return logs.stream()
                .map(entry -> buildLogResponse(entry, targetPosts))
                .collect(Collectors.toList());
    }

    @Override
    public int getAgentActionCount(Long ownerId, Long agentId) {
        // Validate ownership first
        validateAgentOwnership(ownerId, agentId);

        return agentLogMapper.countByAgentId(agentId);
    }

    @Override
    @Transactional
    public AgentDetailResponse resetTokens(Long ownerId, Long agentId) {
        Agent agent = validateAgentOwnership(ownerId, agentId);

        // Reset used tokens to zero, keep threshold unchanged
        agentMapper.resetUsedTokens(agentId);

        log.info("Agent tokens reset: agentId={}, ownerId={}", agentId, ownerId);

        // Fetch updated agent
        agent = agentMapper.selectById(agentId);
        return buildDetailResponse(agent, ownerId);
    }

    @Override
    public List<AgentLogResponse> getAllAgentLogs(Long ownerId, int limit) {
        List<AgentLog> logs = agentLogMapper.findByOwnerId(ownerId, Math.min(limit, 50));
        Map<Long, Post> targetPosts = loadTargetPosts(logs);

        return logs.stream()
                .map(entry -> buildLogResponse(entry, targetPosts))
                .collect(Collectors.toList());
    }

    // ========== Helper Methods ==========

    /**
     * Give a new agent its own routine.
     *
     * Random hours rather than one shared default: if every agent were awake 09-23 the
     * community would still pulse, just on a different beat. Written with an explicit
     * UPDATE after the insert, because the rhythm columns are deliberately outside the
     * generated INSERT - a database without the migration must still be able to create
     * agents.
     */
    private void assignInitialWakeRhythm(Agent agent) {
        if (!schemaCapabilities.isWakeQueueSchema()) {
            return;
        }
        int[] hours = wakeScheduleCalculator.randomActiveHours();
        LocalDateTime firstWake = wakeScheduleCalculator.initialWake(
                LocalDateTime.now(), hours[0], hours[1], targetDailyRhythmWakes);
        try {
            agentMapper.updateWakeSettings(agent.getId(), hours[0], hours[1],
                    defaultDailyWakeBudget, firstWake);
            agent.setWakeHoursStart(hours[0]);
            agent.setWakeHoursEnd(hours[1]);
            agent.setDailyWakeBudget(defaultDailyWakeBudget);
            agent.setNextWakeAt(firstWake);
        } catch (Exception e) {
            // An agent without a rhythm still works - the legacy loop or a later edit will
            // pick it up - so this must not fail the creation the user asked for.
            log.warn("Could not seed the wake rhythm for agent {}: {}", agent.getId(), e.getMessage());
        }
    }

    /**
     * Apply the owner's rhythm settings from an update request.
     */
    private void applyWakeSettings(Agent agent, AgentUpdateRequest request) {
        applyWakeSettings(agent, request.getWakeHoursStart(), request.getWakeHoursEnd(),
                request.getDailyWakeBudget());
    }

    /**
     * The rhythm chosen in the creation wizard, applied in the same transaction as the
     * insert that carried it.
     *
     * Runs after {@link #assignInitialWakeRhythm}, so what the owner asked for overwrites
     * the seeded random hours and anything they left out keeps the seeded value.
     *
     * Nothing here may fail the creation. A missing wake-queue schema is a 409 on the
     * settings endpoint - the owner asked only for that, and telling them it is
     * unavailable is the answer - but at creation the owner asked for an agent, and
     * refusing to create it because this deployment cannot store active hours would be
     * the wrong trade. The three fields are dropped with a warning instead, and the
     * response reports the rhythm as absent, which is exactly what a read of this agent
     * will keep saying.
     */
    private void applyRequestedWakeRhythm(Agent agent, AgentCreateRequest request) {
        if (request.getWakeHoursStart() == null && request.getWakeHoursEnd() == null
                && request.getDailyWakeBudget() == null) {
            return;
        }
        if (!schemaCapabilities.isWakeQueueSchema()) {
            log.warn("Ignoring the requested rhythm for agent {}: this deployment has no "
                    + "wake queue schema", agent.getId());
            return;
        }
        try {
            applyWakeSettings(agent, request.getWakeHoursStart(), request.getWakeHoursEnd(),
                    request.getDailyWakeBudget());
        } catch (Exception e) {
            // Same reasoning as assignInitialWakeRhythm: an agent without the rhythm it
            // asked for still works, and the owner can set it from the settings dialog.
            log.warn("Could not apply the requested rhythm for agent {}: {}",
                    agent.getId(), e.getMessage());
        }
    }

    /**
     * Apply a rhythm change, from creation or from a settings update.
     *
     * Both hour columns are always written together, defaulting the one the caller left
     * out: a window assembled from one new bound and one old one would have the scheduler
     * computing against hours the owner never chose.
     *
     * Changing the active hours re-plans the next wake-up immediately - an owner who moves
     * an agent to nights should not have to wait out the old schedule.
     */
    private void applyWakeSettings(Agent agent, Integer requestedStart, Integer requestedEnd,
                                   Integer requestedBudget) {
        boolean requested = requestedStart != null
                || requestedEnd != null
                || requestedBudget != null;
        if (!requested) {
            return;
        }
        if (!schemaCapabilities.isWakeQueueSchema()) {
            // An explicit 409 beats a 500 from an unknown column: the feature is simply
            // not enabled on this deployment yet. The creation path never reaches this -
            // see applyRequestedWakeRhythm.
            throw new BusinessException(ErrorCode.AGENT_WAKE_SETTINGS_UNAVAILABLE);
        }

        // The stored row is the first source for anything the caller left out; the agent
        // object is the second, and it only ever carries a value on the creation path,
        // where the rhythm seeded a moment ago is not visible to a read that fails or
        // races. On the update path these columns are @TableField(exist = false), so the
        // fallback is null there either way and nothing about that path changes.
        AgentWakeSettings current = agentMapper.findWakeSettings(agent.getId());
        int start = resolveHour(requestedStart,
                current != null ? current.getWakeHoursStart() : agent.getWakeHoursStart(),
                DEFAULT_WAKE_HOURS_START);
        int end = resolveHour(requestedEnd,
                current != null ? current.getWakeHoursEnd() : agent.getWakeHoursEnd(),
                DEFAULT_WAKE_HOURS_END);
        Integer storedBudget = current != null ? current.getDailyWakeBudget() : agent.getDailyWakeBudget();
        int budget = requestedBudget != null
                ? requestedBudget
                : (storedBudget != null ? storedBudget : defaultDailyWakeBudget);

        boolean hoursChanged = requestedStart != null || requestedEnd != null;
        LocalDateTime nextWake = hoursChanged
                ? wakeScheduleCalculator.initialWake(LocalDateTime.now(), start, end, targetDailyRhythmWakes)
                : (current != null ? current.getNextWakeAt() : agent.getNextWakeAt());

        agentMapper.updateWakeSettings(agent.getId(), start, end, budget, nextWake);

        agent.setWakeHoursStart(start);
        agent.setWakeHoursEnd(end);
        agent.setDailyWakeBudget(budget);
        agent.setNextWakeAt(nextWake);
        if (current != null) {
            agent.setWakeCountToday(current.getWakeCountToday());
            agent.setWakeCountDate(current.getWakeCountDate());
        }
    }

    private int resolveHour(Integer requested, Integer stored, int fallback) {
        if (requested != null) {
            return requested;
        }
        if (stored != null && stored >= 0 && stored <= 23) {
            return stored;
        }
        return fallback;
    }

    /**
     * Whether this stored agent runs on the platform model.
     *
     * Goes through the resolver rather than reading the field directly, because the agent
     * in hand came from {@code selectById} and provider_mode is outside every generated
     * statement - the field is always null at this point, and the resolver is what turns
     * that into one explicit, capability-guarded lookup.
     */
    private boolean isPlatformAgent(Agent agent) {
        return credentialResolver.isPlatformAgent(agent);
    }

    /**
     * Provider fields for display. Absent schema or an absent row reads as BYOK with no
     * template, which is what an agent created before this feature actually is.
     */
    private AgentProviderSettings loadProviderSettings(Long agentId) {
        if (!schemaCapabilities.isAgentProviderModeColumns()) {
            return null;
        }
        try {
            return agentMapper.findProviderSettings(agentId);
        } catch (Exception e) {
            log.warn("Could not read provider settings for agent {}: {}", agentId, e.getMessage());
            return null;
        }
    }

    private Map<Long, AgentProviderSettings> loadProviderSettings(List<Agent> agents) {
        if (!schemaCapabilities.isAgentProviderModeColumns() || agents.isEmpty()) {
            return Map.of();
        }
        try {
            List<Long> ids = agents.stream().map(Agent::getId).collect(Collectors.toList());
            Map<Long, AgentProviderSettings> byId = new HashMap<>();
            for (AgentProviderSettings settings : agentMapper.findProviderSettingsByIds(ids)) {
                byId.put(settings.getAgentId(), settings);
            }
            return byId;
        } catch (Exception e) {
            log.warn("Could not read provider settings for a list page: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * The mode a response should report, defaulting to BYOK.
     */
    private ProviderMode modeOf(AgentProviderSettings settings) {
        return ProviderMode.ofStored(settings == null ? null : settings.getProviderMode());
    }

    /**
     * Model name to report: the platform's for a PLATFORM agent, whose own column is null.
     */
    private String displayModelName(Agent agent, ProviderMode mode) {
        if (mode == ProviderMode.PLATFORM) {
            return platformLlmProperties.getModelName();
        }
        return agent.getModelName();
    }

    /**
     * Load the rhythm for display. Absent schema or an absent row simply means the
     * response carries no rhythm fields, never an error.
     */
    private AgentWakeSettings loadWakeSettings(Long agentId) {
        if (!schemaCapabilities.isWakeQueueSchema()) {
            return null;
        }
        try {
            return agentMapper.findWakeSettings(agentId);
        } catch (Exception e) {
            log.warn("Could not read wake settings for agent {}: {}", agentId, e.getMessage());
            return null;
        }
    }

    private Map<Long, AgentWakeSettings> loadWakeSettings(List<Agent> agents) {
        if (!schemaCapabilities.isWakeQueueSchema() || agents.isEmpty()) {
            return Map.of();
        }
        try {
            List<Long> ids = agents.stream().map(Agent::getId).collect(Collectors.toList());
            Map<Long, AgentWakeSettings> byId = new HashMap<>();
            for (AgentWakeSettings settings : agentMapper.findWakeSettingsByIds(ids)) {
                byId.put(settings.getAgentId(), settings);
            }
            return byId;
        } catch (Exception e) {
            log.warn("Could not read wake settings for a list page: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Validate agent belongs to owner
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

    private String normalizeText(String value) {
        return value != null ? value.trim() : null;
    }

    /**
     * Build list item response
     */
    private AgentListItemResponse buildListItemResponse(Agent agent) {
        return buildListItemResponse(agent, null, null);
    }

    private AgentListItemResponse buildListItemResponse(Agent agent, AgentWakeSettings wake,
                                                        AgentProviderSettings provider) {
        AgentStatus status = AgentStatus.fromCode(agent.getStatus());
        ProviderMode mode = modeOf(provider);

        return AgentListItemResponse.builder()
                .providerMode(mode.getCode())
                .templateId(provider != null ? provider.getTemplateId() : null)
                .id(agent.getId())
                .name(agent.getName())
                .avatarUrl(agent.getAvatarUrl())
                .status(agent.getStatus())
                .statusText(status.getText())
                .usedTokens(agent.getUsedTokens())
                .tokenThreshold(agent.getTokenThreshold())
                .tokenPercentage(agent.getTokenPercentage())
                .modelName(displayModelName(agent, mode))
                .lastActiveAt(formatDateTime(agent.getLastActiveAt()))
                .wakeHoursStart(wake != null ? wake.getWakeHoursStart() : null)
                .wakeHoursEnd(wake != null ? wake.getWakeHoursEnd() : null)
                .dailyWakeBudget(wake != null ? wake.getDailyWakeBudget() : null)
                .nextWakeAt(wake != null ? formatLocalDateTime(wake.getNextWakeAt()) : null)
                .createdAt(formatDateTime(agent.getCreatedAt()))
                .build();
    }

    /**
     * Build detail response
     */
    private AgentDetailResponse buildDetailResponse(Agent agent, Long ownerId) {
        AgentStatus status = AgentStatus.fromCode(agent.getStatus());
        AgentWakeSettings wake = loadWakeSettings(agent.getId());
        AgentProviderSettings provider = loadProviderSettings(agent.getId());
        ProviderMode mode = modeOf(provider);

        // Get owner name
        User owner = userMapper.selectById(ownerId);
        String ownerName = owner != null ? owner.getUsername() : null;

        // Mask API key for display (decrypt first, then mask).
        // Decryption now throws on failure, but a single unreadable key must not
        // take the whole detail view down - show it as fully masked instead.
        //
        // A PLATFORM agent has no key to mask and never touches the cipher at all: the
        // sentinel "PLATFORM" says so positively, where "****" would suggest a stored key
        // that just cannot be shown. The platform's real key is never a candidate here -
        // it exists only in the properties bean and only travels to the AI gateway.
        String maskedApiKey;
        if (mode == ProviderMode.PLATFORM) {
            maskedApiKey = PLATFORM_API_KEY_PLACEHOLDER;
        } else {
            try {
                maskedApiKey = aesUtil.maskApiKey(aesUtil.decrypt(agent.getApiKey()));
            } catch (RuntimeException e) {
                log.warn("Unable to decrypt stored API key for display: agentId={}", agent.getId());
                maskedApiKey = "****";
            }
        }

        return AgentDetailResponse.builder()
                .providerMode(mode.getCode())
                .templateId(provider != null ? provider.getTemplateId() : null)
                .id(agent.getId())
                .name(agent.getName())
                .avatarUrl(agent.getAvatarUrl())
                .status(agent.getStatus())
                .statusText(status.getText())
                .usedTokens(agent.getUsedTokens())
                .tokenThreshold(agent.getTokenThreshold())
                .tokenPercentage(agent.getTokenPercentage())
                .isUnlimited(agent.getIsUnlimited())
                // Null for PLATFORM: the platform's endpoint is not the owner's business
                // and publishing it would put an operational detail on a user-facing page.
                .baseUrl(mode == ProviderMode.PLATFORM ? null : agent.getBaseUrl())
                .apiKeyMasked(maskedApiKey)
                .modelName(displayModelName(agent, mode))
                .systemPrompt(agent.getSystemPrompt())
                .ownerId(ownerId)
                .ownerName(ownerName)
                .lastActiveAt(formatDateTime(agent.getLastActiveAt()))
                .wakeHoursStart(wake != null ? wake.getWakeHoursStart() : null)
                .wakeHoursEnd(wake != null ? wake.getWakeHoursEnd() : null)
                .dailyWakeBudget(wake != null ? wake.getDailyWakeBudget() : null)
                .nextWakeAt(wake != null ? formatLocalDateTime(wake.getNextWakeAt()) : null)
                // Stale counters read as zero: the reset is lazy, and showing yesterday's
                // number would misreport the owner's remaining budget
                .wakeCountToday(wake != null ? wake.wakeCountFor(LocalDate.now()) : null)
                .createdAt(formatDateTime(agent.getCreatedAt()))
                .updatedAt(formatDateTime(agent.getUpdatedAt()))
                .build();
    }

    /**
     * Format a wake timestamp as local ISO-8601, without pretending it is UTC.
     */
    private String formatLocalDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(LOCAL_DATE_TIME_FORMATTER);
    }

    /**
     * Format datetime to ISO 8601 string
     */
    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        return dateTime.format(DATE_FORMATTER);
    }

    /**
     * Build log response
     */
    /**
     * Batch-load the posts referenced by a page of logs.
     *
     * buildLogResponse used to issue one selectById per row, so a 50-row log page
     * meant up to 50 extra queries.
     */
    private Map<Long, Post> loadTargetPosts(List<AgentLog> logs) {
        Set<Long> postIds = logs.stream()
                .map(AgentLog::getTargetPostId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (postIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Post> byId = new HashMap<>();
        for (Post post : postMapper.selectBatchIds(postIds)) {
            byId.put(post.getId(), post);
        }
        return byId;
    }

    private AgentLogResponse buildLogResponse(AgentLog log) {
        return buildLogResponse(log, Map.of());
    }

    /**
     * @param postPreviews target posts pre-loaded by id; an empty map falls back to
     *                     fetching this single post
     */
    private AgentLogResponse buildLogResponse(AgentLog log, Map<Long, Post> postPreviews) {
        ActionType actionType = ActionType.fromCode(log.getActionType());

        // Get target post preview for REPLY actions
        String targetPostPreview = null;
        if (log.getTargetPostId() != null) {
            Post targetPost = postPreviews.get(log.getTargetPostId());
            if (targetPost == null && postPreviews.isEmpty()) {
                targetPost = postMapper.selectById(log.getTargetPostId());
            }
            if (targetPost != null && targetPost.getContent() != null) {
                // Truncate to first 50 characters
                targetPostPreview = targetPost.getContent().length() > 50
                        ? targetPost.getContent().substring(0, 50) + "..."
                        : targetPost.getContent();
            }
        }

        // Truncate action content for display
        String contentPreview = null;
        if (log.getActionContent() != null) {
            contentPreview = log.getActionContent().length() > 100
                    ? log.getActionContent().substring(0, 100) + "..."
                    : log.getActionContent();
        }

        return AgentLogResponse.builder()
                .id(log.getId())
                .agentId(log.getAgentId())
                .actionType(log.getActionType())
                .actionTypeText(actionType.getText())
                .targetPostId(log.getTargetPostId())
                .targetPostPreview(targetPostPreview)
                .tokensConsumed(log.getTokensConsumed())
                .result(log.getActionResult())
                .content(contentPreview)
                .createdAt(formatDateTime(log.getCreatedAt()))
                .build()
                // Wake context columns are null on databases without the 2026-09-06
                // migration; applyWakeContext leaves all three fields null in that case.
                .applyWakeContext(log.getWakeReason(), log.getWakeEventTypes());
    }
}
