package com.pulse.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.dto.response.AgentTemplateResponse;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The built-in persona templates, loaded once at startup and served read-only.
 *
 * A classpath file rather than a database table: templates are product copy that ships
 * with the build, so a table would need a migration, a seed and a way to reconcile the
 * two whenever the copy changed. They are validated here rather than where they are used,
 * because the only useful moment to find out that a template is malformed is before the
 * application accepts traffic - a template that fails the AI side's own prompt-length
 * check would otherwise surface as a create failure for whoever happened to pick it.
 *
 * The returned list is immutable and shared: nothing about a template is per-request, and
 * copying it on every call to the endpoint would allocate for no reason.
 */
@Slf4j
@Component
public class AgentTemplateCatalog {

    static final String RESOURCE_PATH = "agent-templates.json";

    /**
     * The AI side validates system_prompt at 10-2000 characters, and so does
     * AgentCreateRequest. A template outside that range would produce an agent the
     * gateway refuses on its first wake-up, which is the worst place to find out.
     */
    static final int PROMPT_MIN_LENGTH = 10;
    static final int PROMPT_MAX_LENGTH = 2000;

    /** Column width of agents.template_id. */
    static final int TEMPLATE_ID_MAX_LENGTH = 64;

    private List<AgentTemplateResponse> templates = List.of();
    private Map<String, AgentTemplateResponse> byId = Map.of();

    @PostConstruct
    public void load() {
        List<AgentTemplateResponse> parsed = readFile();
        validate(parsed);

        this.templates = List.copyOf(parsed);
        this.byId = parsed.stream().collect(Collectors.toUnmodifiableMap(
                AgentTemplateResponse::getTemplateId, Function.identity()));

        log.info("Loaded {} agent persona templates: {}", templates.size(),
                templates.stream().map(AgentTemplateResponse::getTemplateId).collect(Collectors.toList()));
    }

    /**
     * Every template, in file order. Immutable.
     */
    public List<AgentTemplateResponse> getTemplates() {
        return templates;
    }

    /**
     * Look one up by id.
     *
     * Used by the create path to validate a submitted template_id. An unknown id is a
     * caller error, not a reason to invent a template, so this returns empty rather than
     * a fallback.
     */
    public Optional<AgentTemplateResponse> findById(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(templateId.trim()));
    }

    public boolean exists(String templateId) {
        return findById(templateId).isPresent();
    }

    private List<AgentTemplateResponse> readFile() {
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        if (!resource.exists()) {
            // Not fatal: an installation without the file simply offers no templates and
            // owners write their own prompt, which is the pre-existing behaviour.
            log.warn("{} is not on the classpath; no persona templates will be offered", RESOURCE_PATH);
            return List.of();
        }
        try (InputStream in = resource.getInputStream()) {
            // A local ObjectMapper on purpose: this must not depend on how the web layer's
            // shared mapper happens to be configured, and it runs exactly once.
            TemplateFile file = new ObjectMapper().readValue(in, TemplateFile.class);
            return file.getTemplates() == null ? new ArrayList<>() : file.getTemplates();
        } catch (Exception e) {
            // A malformed file is a build defect, and shipping it silently would leave the
            // create form with an empty picker and no explanation anywhere.
            throw new IllegalStateException("Could not read " + RESOURCE_PATH
                    + "; the agent persona templates are malformed", e);
        }
    }

    /**
     * Reject anything that would fail later, at a worse moment.
     */
    private void validate(List<AgentTemplateResponse> parsed) {
        Set<String> seen = new HashSet<>();
        for (AgentTemplateResponse template : parsed) {
            String id = template.getTemplateId();
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("A persona template has no template_id");
            }
            if (id.length() > TEMPLATE_ID_MAX_LENGTH) {
                throw new IllegalStateException("template_id is longer than the "
                        + TEMPLATE_ID_MAX_LENGTH + "-character column: " + id);
            }
            if (!seen.add(id)) {
                // Duplicated ids would make findById depend on file order, and an agent's
                // stored template_id would stop identifying one template.
                throw new IllegalStateException("Duplicate persona template_id: " + id);
            }
            if (template.getName() == null || template.getName().isBlank()) {
                throw new IllegalStateException("Persona template " + id + " has no name");
            }
            String prompt = template.getSystemPrompt();
            int length = prompt == null ? 0 : prompt.length();
            if (length < PROMPT_MIN_LENGTH || length > PROMPT_MAX_LENGTH) {
                throw new IllegalStateException("Persona template " + id + " has a system_prompt of "
                        + length + " characters; the AI side accepts " + PROMPT_MIN_LENGTH + "-"
                        + PROMPT_MAX_LENGTH);
            }
        }
    }

    /**
     * File envelope. Unknown keys are ignored so the file can carry a "_comment" block
     * explaining itself to the next reader.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TemplateFile {
        private List<AgentTemplateResponse> templates;
    }
}
