package com.pulse.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.config.HotNewsProperties;
import com.pulse.dto.request.HotNewsIngestRequest;
import com.pulse.dto.request.HotNewsItemRequest;
import com.pulse.dto.request.HotNewsSectionRequest;
import com.pulse.dto.response.HotNewsItemResponse;
import com.pulse.dto.response.HotNewsReportResponse;
import com.pulse.dto.response.HotNewsSectionResponse;
import com.pulse.entity.HotNewsItem;
import com.pulse.entity.HotNewsReport;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.HotNewsItemMapper;
import com.pulse.mapper.HotNewsReportMapper;
import com.pulse.service.HotNewsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Daily hot news service implementation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotNewsServiceImpl implements HotNewsService {

    private static final String DEFAULT_SOURCE = "hermes";
    private static final String DEFAULT_TITLE_PREFIX = "TECH_DAILY ";
    private static final String LATEST_CACHE_KEY = "pulse:hot-news:latest";
    private static final String DETAIL_CACHE_KEY_PREFIX = "pulse:hot-news:detail:";
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final Map<String, String> SECTION_LABELS = Map.ofEntries(
            Map.entry("github", "GitHub"),
            Map.entry("hn", "Hacker News"),
            Map.entry("hacker_news", "Hacker News"),
            Map.entry("ai", "AI 行业"),
            Map.entry("ai_industry", "AI 行业"),
            Map.entry("developer_ecosystem", "开发者生态"),
            Map.entry("dev_ecosystem", "开发者生态"),
            Map.entry("security", "安全隐私"),
            Map.entry("security_privacy", "安全隐私"),
            Map.entry("big_tech", "大公司"),
            Map.entry("funding", "投融资"),
            Map.entry("summary", "今日总结")
    );

    private final HotNewsReportMapper reportMapper;
    private final HotNewsItemMapper itemMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final HotNewsProperties properties;
    private final HotNewsMarkdownParser markdownParser;

    @Override
    @Transactional
    public HotNewsReportResponse ingest(HotNewsIngestRequest request, String token) {
        validateIngestToken(token);
        if (request == null || request.getReportDate() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "report_date is required");
        }

        String source = normalizeSource(request.getSource());
        HotNewsReport report = reportMapper.findByReportDateAndSource(request.getReportDate(), source);
        boolean exists = report != null;
        if (!exists) {
            report = new HotNewsReport();
            report.setReportDate(request.getReportDate());
            report.setSource(source);
            report.setDeleted(0);
        }

        fillReport(report, request, source);
        if (exists) {
            reportMapper.updateById(report);
        } else {
            reportMapper.insert(report);
            report = ensureReportId(report, request, source);
        }

        itemMapper.deleteByReportId(report.getId());
        List<HotNewsItem> items = buildItems(request, report.getId());
        for (HotNewsItem item : items) {
            itemMapper.insert(item);
        }

        HotNewsReportResponse response = buildResponse(report, items);
        cacheReport(response);
        log.info("Daily hot news ingested: reportId={}, date={}, source={}, items={}",
                response.getReportId(), response.getReportDate(), response.getSource(), response.getItemCount());
        return response;
    }

    @Override
    public HotNewsReportResponse getLatest() {
        HotNewsReportResponse cached = readCache(LATEST_CACHE_KEY);
        if (cached != null) {
            return cached;
        }

        HotNewsReport report = reportMapper.findLatestReport();
        if (report == null) {
            throw new BusinessException(ErrorCode.HOT_NEWS_NOT_FOUND);
        }
        HotNewsReportResponse response = buildResponse(report, itemMapper.findByReportId(report.getId()));
        cacheReport(response);
        return response;
    }

    @Override
    public HotNewsReportResponse getDetail(Long reportId) {
        if (reportId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "reportId is required");
        }

        String cacheKey = detailCacheKey(reportId);
        HotNewsReportResponse cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        HotNewsReport report = reportMapper.selectById(reportId);
        if (report == null || Objects.equals(report.getDeleted(), 1)) {
            throw new BusinessException(ErrorCode.HOT_NEWS_NOT_FOUND);
        }

        HotNewsReportResponse response = buildResponse(report, itemMapper.findByReportId(reportId));
        writeCache(cacheKey, response);
        return response;
    }

    private void validateIngestToken(String actualToken) {
        String expectedToken = properties.getIngestToken();
        if (!StringUtils.hasText(expectedToken)
                || "change_me".equals(expectedToken)
                || !StringUtils.hasText(actualToken)
                || !constantTimeEquals(expectedToken, actualToken)) {
            throw new BusinessException(ErrorCode.HOT_NEWS_TOKEN_INVALID);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private void fillReport(HotNewsReport report, HotNewsIngestRequest request, String source) {
        report.setReportDate(request.getReportDate());
        report.setTitle(StringUtils.hasText(request.getTitle())
                ? request.getTitle().trim()
                : DEFAULT_TITLE_PREFIX + request.getReportDate());
        // Derive the summary from the markdown when the publisher omits it, so the
        // sidebar card is not left blank (see HotNewsMarkdownParser)
        String summary = trimToNull(request.getSummary());
        if (summary == null) {
            summary = markdownParser.extractSummary(request.getRawMarkdown());
            if (summary != null) {
                log.info("Daily report summary derived from markdown: date={}", request.getReportDate());
            }
        }
        report.setSummary(summary);
        report.setRawMarkdown(trimToNull(request.getRawMarkdown()));
        report.setSource(source);
        report.setPublishedAt(request.getPublishedAt());
    }

    /**
     * Sections as sent by the publisher, or parsed out of the markdown when it sent
     * none. Production reports arrive with raw_markdown only, which left every
     * report with an empty sections array and a detail page that could not render
     * anything structured.
     */
    private List<HotNewsSectionRequest> resolveSections(HotNewsIngestRequest request) {
        List<HotNewsSectionRequest> provided = request.getSections();
        if (provided != null && !provided.isEmpty()) {
            return provided;
        }
        List<HotNewsSectionRequest> parsed = markdownParser.extractSections(request.getRawMarkdown());
        if (!parsed.isEmpty()) {
            log.info("Daily report sections derived from markdown: date={}, sections={}",
                    request.getReportDate(), parsed.size());
        }
        return parsed;
    }

    private HotNewsReport ensureReportId(HotNewsReport report, HotNewsIngestRequest request, String source) {
        if (report.getId() != null) {
            return report;
        }
        HotNewsReport inserted = reportMapper.findByReportDateAndSource(request.getReportDate(), source);
        if (inserted == null || inserted.getId() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "hot news report id missing after insert");
        }
        return inserted;
    }

    private List<HotNewsItem> buildItems(HotNewsIngestRequest request, Long reportId) {
        List<HotNewsItem> items = new ArrayList<>();
        List<HotNewsSectionRequest> sections = resolveSections(request);
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            HotNewsSectionRequest sectionRequest = sections.get(sectionIndex);
            String section = normalizeSection(sectionRequest.getSection());
            List<HotNewsItemRequest> sectionItems = sectionRequest.getItems() == null
                    ? List.of()
                    : sectionRequest.getItems();
            for (int itemIndex = 0; itemIndex < sectionItems.size(); itemIndex++) {
                HotNewsItemRequest itemRequest = sectionItems.get(itemIndex);
                HotNewsItem item = new HotNewsItem();
                item.setReportId(reportId);
                item.setSection(section);
                item.setSectionOrder(sectionIndex);
                item.setRank(itemRequest.getRank() != null ? itemRequest.getRank() : itemIndex + 1);
                item.setTitle(itemRequest.getTitle().trim());
                item.setTopic(trimToNull(itemRequest.getTopic()));
                item.setUrl(trimToNull(itemRequest.getUrl()));
                item.setScore(itemRequest.getScore());
                item.setBrief(trimToNull(itemRequest.getBrief()));
                item.setPayloadJson(itemRequest.getPayloadJson());
                items.add(item);
            }
        }
        return items;
    }

    private HotNewsReportResponse buildResponse(HotNewsReport report, List<HotNewsItem> items) {
        List<HotNewsItem> safeItems = items == null ? List.of() : items;
        LinkedHashMap<String, HotNewsSectionResponse> sections = new LinkedHashMap<>();
        for (HotNewsItem item : safeItems) {
            String section = normalizeSection(item.getSection());
            HotNewsSectionResponse sectionResponse = sections.computeIfAbsent(section, key ->
                    HotNewsSectionResponse.builder()
                            .section(key)
                            .sectionLabel(sectionLabel(key))
                            .items(new ArrayList<>())
                            .build());
            sectionResponse.getItems().add(toItemResponse(item));
        }

        return HotNewsReportResponse.builder()
                .reportId(report.getId())
                .reportDate(report.getReportDate() != null ? report.getReportDate().toString() : null)
                .title(report.getTitle())
                .summary(report.getSummary())
                .rawMarkdown(report.getRawMarkdown())
                .source(report.getSource())
                .publishedAt(formatDateTime(report.getPublishedAt()))
                .updatedAt(formatDateTime(report.getUpdatedAt()))
                .sectionCount(sections.size())
                .itemCount(safeItems.size())
                .sections(new ArrayList<>(sections.values()))
                .build();
    }

    private HotNewsItemResponse toItemResponse(HotNewsItem item) {
        return HotNewsItemResponse.builder()
                .itemId(item.getId())
                .section(normalizeSection(item.getSection()))
                .rank(item.getRank())
                .title(item.getTitle())
                .topic(item.getTopic())
                .url(item.getUrl())
                .score(item.getScore())
                .brief(item.getBrief())
                .payloadJson(item.getPayloadJson())
                .build();
    }

    private void cacheReport(HotNewsReportResponse response) {
        writeCache(detailCacheKey(response.getReportId()), response);
        writeCache(LATEST_CACHE_KEY, response);
    }

    private HotNewsReportResponse readCache(String key) {
        try {
            String payload = redisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(payload)) {
                return null;
            }
            return objectMapper.readValue(payload, HotNewsReportResponse.class);
        } catch (Exception e) {
            log.warn("Failed to read hot news cache: key={}", key, e);
            return null;
        }
    }

    private void writeCache(String key, HotNewsReportResponse response) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(response), cacheTtl());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize hot news cache: key={}", key, e);
        } catch (Exception e) {
            log.warn("Failed to write hot news cache: key={}", key, e);
        }
    }

    private Duration cacheTtl() {
        return Duration.ofHours(Math.max(1, properties.getCacheTtlHours()));
    }

    private String detailCacheKey(Long reportId) {
        return DETAIL_CACHE_KEY_PREFIX + reportId;
    }

    private String normalizeSource(String source) {
        return StringUtils.hasText(source)
                ? source.trim().toLowerCase(Locale.ROOT)
                : DEFAULT_SOURCE;
    }

    private String normalizeSection(String section) {
        return StringUtils.hasText(section)
                ? section.trim().toLowerCase(Locale.ROOT).replace('-', '_')
                : "misc";
    }

    private String sectionLabel(String section) {
        return SECTION_LABELS.getOrDefault(section, section.toUpperCase(Locale.ROOT));
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String formatDateTime(java.time.LocalDateTime value) {
        return value != null ? value.format(DATE_TIME_FORMATTER) : null;
    }
}
