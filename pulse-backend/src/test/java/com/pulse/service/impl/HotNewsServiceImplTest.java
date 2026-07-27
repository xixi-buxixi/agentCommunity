package com.pulse.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pulse.config.HotNewsProperties;
import com.pulse.dto.request.HotNewsIngestRequest;
import com.pulse.dto.request.HotNewsItemRequest;
import com.pulse.dto.request.HotNewsSectionRequest;
import com.pulse.dto.response.HotNewsReportResponse;
import com.pulse.entity.HotNewsReport;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.HotNewsItemMapper;
import com.pulse.mapper.HotNewsReportMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotNewsServiceImplTest {

    private final HotNewsReportMapper reportMapper = mock(HotNewsReportMapper.class);
    private final HotNewsItemMapper itemMapper = mock(HotNewsItemMapper.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = (ValueOperations<String, String>) mock(ValueOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final HotNewsProperties properties = new HotNewsProperties();

    private final HotNewsServiceImpl service;

    HotNewsServiceImplTest() {
        properties.setIngestToken("secret-token");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new HotNewsServiceImpl(reportMapper, itemMapper, redisTemplate, objectMapper, properties,
                new HotNewsMarkdownParser());
    }

    @Test
    void ingestRejectsInvalidHermesToken() {
        HotNewsIngestRequest request = request("hermes");

        assertThatThrownBy(() -> service.ingest(request, "wrong-token"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.HOT_NEWS_TOKEN_INVALID.getCode());
    }

    @Test
    void ingestUpdatesExistingDailyReportAndReplacesItems() {
        HotNewsReport existing = report(42L, "hermes");
        when(reportMapper.findByReportDateAndSource(LocalDate.of(2026, 6, 1), "hermes")).thenReturn(existing);
        when(itemMapper.findByReportId(42L)).thenReturn(List.of());

        HotNewsReportResponse response = service.ingest(request("hermes"), "secret-token");

        assertThat(response.getReportId()).isEqualTo(42L);
        assertThat(response.getReportDate()).isEqualTo("2026-06-01");
        assertThat(response.getItemCount()).isEqualTo(2);
        verify(reportMapper).updateById(existing);
        verify(itemMapper).deleteByReportId(42L);
        verify(itemMapper, times(2)).insert(any());
    }

    @Test
    void latestFallsBackToMysqlWhenRedisSnapshotMissing() {
        HotNewsReport latest = report(42L, "hermes");
        when(valueOperations.get("pulse:hot-news:latest")).thenReturn(null);
        when(reportMapper.findLatestReport()).thenReturn(latest);
        when(itemMapper.findByReportId(42L)).thenReturn(List.of());

        HotNewsReportResponse response = service.getLatest();

        assertThat(response.getReportId()).isEqualTo(42L);
        assertThat(response.getTitle()).isEqualTo("TECH_DAILY");
        verify(reportMapper).findLatestReport();
    }

    private HotNewsIngestRequest request(String source) {
        HotNewsItemRequest github = new HotNewsItemRequest();
        github.setRank(1);
        github.setTitle("Fast repo hits 100k stars");
        github.setTopic("GitHub");
        github.setUrl("https://github.com/example/fast");
        github.setScore(98);
        github.setBrief("A developer tool is trending.");
        github.setPayloadJson(Map.of("stars", 100000));

        HotNewsItemRequest ai = new HotNewsItemRequest();
        ai.setTitle("New model release");
        ai.setBrief("A model provider shipped an update.");

        HotNewsSectionRequest githubSection = new HotNewsSectionRequest();
        githubSection.setSection("github");
        githubSection.setItems(List.of(github));

        HotNewsSectionRequest aiSection = new HotNewsSectionRequest();
        aiSection.setSection("ai");
        aiSection.setItems(List.of(ai));

        HotNewsIngestRequest request = new HotNewsIngestRequest();
        request.setReportDate(LocalDate.of(2026, 6, 1));
        request.setTitle("TECH_DAILY");
        request.setSummary("Two important technical updates.");
        request.setRawMarkdown("# TECH_DAILY");
        request.setSource(source);
        request.setPublishedAt(LocalDateTime.of(2026, 6, 1, 17, 0));
        request.setSections(List.of(githubSection, aiSection));
        return request;
    }

    private HotNewsReport report(Long id, String source) {
        HotNewsReport report = new HotNewsReport();
        report.setId(id);
        report.setReportDate(LocalDate.of(2026, 6, 1));
        report.setTitle("TECH_DAILY");
        report.setSummary("Two important technical updates.");
        report.setRawMarkdown("# TECH_DAILY");
        report.setSource(source);
        report.setPublishedAt(LocalDateTime.of(2026, 6, 1, 17, 0));
        report.setCreatedAt(LocalDateTime.of(2026, 6, 1, 17, 1));
        report.setUpdatedAt(LocalDateTime.of(2026, 6, 1, 17, 2));
        return report;
    }
}
