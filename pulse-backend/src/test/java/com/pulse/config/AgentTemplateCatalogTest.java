package com.pulse.config;

import com.pulse.dto.response.AgentTemplateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shipped persona templates, checked against the contract they have to satisfy
 * elsewhere.
 *
 * The prompt-length bound is the one worth having a test for: it is the AI side's own
 * validation range, enforced by a service this build never talks to. A template that
 * violated it would look perfectly fine here and fail at the first wake-up of whoever
 * picked it.
 */
class AgentTemplateCatalogTest {

    private final AgentTemplateCatalog catalog = new AgentTemplateCatalog();

    @BeforeEach
    void load() {
        catalog.load();
    }

    @Test
    void sixTemplatesShip() {
        assertThat(catalog.getTemplates()).hasSize(6);
    }

    @Test
    void everyTemplateIsCompleteAndDistinct() {
        assertThat(catalog.getTemplates())
                .allSatisfy(template -> {
                    assertThat(template.getTemplateId()).isNotBlank();
                    assertThat(template.getName()).isNotBlank();
                    assertThat(template.getTagline()).isNotBlank();
                    assertThat(template.getDescription()).isNotBlank();
                    assertThat(template.getTags()).isNotEmpty();
                })
                .extracting(AgentTemplateResponse::getTemplateId)
                .doesNotHaveDuplicates();
    }

    /**
     * 10-2000 characters: the AI side's system_prompt validation, and the same range
     * AgentCreateRequest enforces on whatever the owner finally submits.
     */
    @Test
    void everyPromptFitsTheGatewaysValidationRange() {
        assertThat(catalog.getTemplates()).allSatisfy(template ->
                assertThat(template.getSystemPrompt().length())
                        .as("system_prompt length of %s", template.getTemplateId())
                        .isBetween(AgentTemplateCatalog.PROMPT_MIN_LENGTH,
                                AgentTemplateCatalog.PROMPT_MAX_LENGTH));
    }

    @Test
    void suggestedWakeHoursAreValidHoursOfDay() {
        assertThat(catalog.getTemplates()).allSatisfy(template -> {
            assertThat(template.getSuggestedWakeHoursStart()).isBetween(0, 24);
            assertThat(template.getSuggestedWakeHoursEnd()).isBetween(0, 24);
        });
    }

    @Test
    void aTemplateCanBeLookedUpByItsId() {
        assertThat(catalog.findById("tech-critic")).isPresent();
        assertThat(catalog.exists("tech-critic")).isTrue();
    }

    @Test
    void anUnknownIdIsAbsentRatherThanASubstitute() {
        assertThat(catalog.findById("does-not-exist")).isEmpty();
        assertThat(catalog.findById(null)).isEmpty();
        assertThat(catalog.findById("  ")).isEmpty();
        assertThat(catalog.exists("does-not-exist")).isFalse();
    }

    /**
     * A malformed template is a build defect. Failing startup is the only moment at which
     * anyone will notice - shipping it leaves an empty picker with no explanation.
     */
    @Test
    void aTooShortPromptFailsValidation() {
        AgentTemplateCatalog.TemplateFile file = new AgentTemplateCatalog.TemplateFile();
        file.setTemplates(java.util.List.of(AgentTemplateResponse.builder()
                .templateId("broken")
                .name("坏模板")
                .systemPrompt("short")
                .build()));

        assertThatThrownBy(() -> org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(catalog, "validate", file.getTemplates()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken");
    }

    @Test
    void aDuplicateIdFailsValidation() {
        AgentTemplateResponse one = AgentTemplateResponse.builder()
                .templateId("same").name("A").systemPrompt("这是一个足够长的系统提示词内容").build();
        AgentTemplateResponse two = AgentTemplateResponse.builder()
                .templateId("same").name("B").systemPrompt("这是另一个足够长的系统提示词内容").build();

        assertThatThrownBy(() -> org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(catalog, "validate", java.util.List.of(one, two)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }
}
