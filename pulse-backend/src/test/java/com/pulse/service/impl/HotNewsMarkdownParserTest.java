package com.pulse.service.impl;

import com.pulse.dto.request.HotNewsItemRequest;
import com.pulse.dto.request.HotNewsSectionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for M21: production reports arrive with raw_markdown only, so the
 * structured fields have to be derived from it.
 */
class HotNewsMarkdownParserTest {

    private final HotNewsMarkdownParser parser = new HotNewsMarkdownParser();

    private static final String REPORT = """
            # TECH_DAILY 2026-07-26

            今天的主线是数据库与调度：几篇文章都在讨论如何在小规格机器上稳住尾延迟。

            ## Hacker News

            1. [Postgres 18 released](https://example.com/pg18) (420 分)
            2. [Why your p99 is a lie](https://example.com/p99) score: 310
            3. 一篇没有链接的讨论

            ## GitHub Trending

            - [shedlock](https://github.com/lukas-krecan/ShedLock) 分布式调度锁
            - **ruff** 极快的 Python linter

            ```
            # 这段代码块里的 1. 不应该被当成条目
            1. not an item
            ```
            """;

    @Test
    void summaryIsTheFirstProseParagraph() {
        String summary = parser.extractSummary(REPORT);

        assertThat(summary).startsWith("今天的主线是数据库与调度");
        // Headings and list items must not leak into the summary
        assertThat(summary).doesNotContain("TECH_DAILY");
        assertThat(summary).doesNotContain("Postgres 18");
    }

    @Test
    void summaryIsNullWhenThereIsNoProse() {
        assertThat(parser.extractSummary("# Title\n\n- item one\n- item two")).isNull();
        assertThat(parser.extractSummary("")).isNull();
        assertThat(parser.extractSummary(null)).isNull();
    }

    @Test
    void sectionsFollowTheHeadings() {
        List<HotNewsSectionRequest> sections = parser.extractSections(REPORT);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getSection()).isEqualTo("Hacker News");
        assertThat(sections.get(1).getSection()).isEqualTo("GitHub Trending");
    }

    @Test
    void itemsCarryTitleUrlScoreAndRank() {
        List<HotNewsItemRequest> items = parser.extractSections(REPORT).get(0).getItems();

        assertThat(items).hasSize(3);

        HotNewsItemRequest first = items.get(0);
        assertThat(first.getTitle()).isEqualTo("Postgres 18 released");
        assertThat(first.getUrl()).isEqualTo("https://example.com/pg18");
        assertThat(first.getScore()).isEqualTo(420);
        assertThat(first.getRank()).isEqualTo(1);

        HotNewsItemRequest second = items.get(1);
        assertThat(second.getScore()).isEqualTo(310);
        assertThat(second.getTitle()).isEqualTo("Why your p99 is a lie");

        // An item without a link is still a valid item
        HotNewsItemRequest third = items.get(2);
        assertThat(third.getTitle()).isEqualTo("一篇没有链接的讨论");
        assertThat(third.getUrl()).isNull();
    }

    @Test
    void inlineMarkupIsStrippedFromTitles() {
        List<HotNewsItemRequest> items = parser.extractSections(REPORT).get(1).getItems();

        assertThat(items.get(1).getTitle()).isEqualTo("ruff 极快的 Python linter");
    }

    @Test
    void fencedCodeBlocksAreNotParsedAsItems() {
        List<HotNewsSectionRequest> sections = parser.extractSections(REPORT);

        assertThat(sections.get(1).getItems())
                .extracting(HotNewsItemRequest::getTitle)
                .doesNotContain("not an item");
    }

    @Test
    void markdownWithoutListsProducesNoSections() {
        assertThat(parser.extractSections("# Title\n\njust a paragraph")).isEmpty();
        assertThat(parser.extractSections(null)).isEmpty();
    }

    @Test
    void listItemsBeforeAnyHeadingStillFormASection() {
        List<HotNewsSectionRequest> sections = parser.extractSections("- one\n- two");

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getSection()).isEqualTo("HIGHLIGHTS");
        assertThat(sections.get(0).getItems()).hasSize(2);
    }
}
