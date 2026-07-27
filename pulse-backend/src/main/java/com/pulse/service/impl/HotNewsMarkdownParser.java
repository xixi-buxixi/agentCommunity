package com.pulse.service.impl;

import com.pulse.dto.request.HotNewsItemRequest;
import com.pulse.dto.request.HotNewsSectionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives the structured parts of a daily report from its markdown.
 *
 * Why (M21): in production the report arrives with raw_markdown filled in but
 * summary null and sections empty, because the Hermes publisher only sends the
 * markdown. The detail page iterates sections, so the "structured daily report"
 * rendered as one undifferentiated blob and the sidebar card had no summary.
 *
 * Anything the publisher does send is authoritative; this only fills the gaps.
 */
@Slf4j
@Component
public class HotNewsMarkdownParser {

    /** "## Section title" or "### Section title" */
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{2,4})\\s+(.+?)\\s*#*\\s*$");

    /** "1. text", "- text", "* text" */
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s{0,3}(?:\\d+[.)]|[-*+])\\s+(.*)$");

    /** "[title](url)" */
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^]]+)]\\((https?://[^)\\s]+)\\)");

    /** A bare url anywhere in the line */
    private static final Pattern BARE_URL = Pattern.compile("(https?://[^\\s)\\]]+)");

    /** Trailing "(123 分)" / "score: 123" style annotations */
    private static final Pattern SCORE = Pattern.compile("(?:score|分数|热度)\\s*[:：]?\\s*(\\d{1,7})|\\((\\d{1,7})\\s*分\\)",
            Pattern.CASE_INSENSITIVE);

    private static final int MAX_SUMMARY_LENGTH = 400;
    private static final int MAX_TITLE_LENGTH = 300;
    private static final int MAX_SECTIONS = 20;
    private static final int MAX_ITEMS_PER_SECTION = 30;

    /**
     * Extract a one-paragraph summary: the first non-heading, non-list prose block.
     *
     * @return the summary, or null when the markdown has no usable prose
     */
    public String extractSummary(String rawMarkdown) {
        if (!StringUtils.hasText(rawMarkdown)) {
            return null;
        }

        StringBuilder paragraph = new StringBuilder();
        for (String line : rawMarkdown.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (paragraph.length() > 0) {
                    break;
                }
                continue;
            }
            if (trimmed.startsWith("#") || LIST_ITEM.matcher(trimmed).matches()
                    || trimmed.startsWith(">") || trimmed.startsWith("|")
                    || trimmed.startsWith("```") || trimmed.startsWith("---")) {
                if (paragraph.length() > 0) {
                    break;
                }
                continue;
            }
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(stripInlineMarkup(trimmed));
            if (paragraph.length() >= MAX_SUMMARY_LENGTH) {
                break;
            }
        }

        if (paragraph.length() == 0) {
            return null;
        }
        String summary = paragraph.toString().trim();
        return summary.length() > MAX_SUMMARY_LENGTH
                ? summary.substring(0, MAX_SUMMARY_LENGTH)
                : summary;
    }

    /**
     * Split the markdown into sections of list items.
     *
     * @return sections in document order; empty when nothing list-like was found
     */
    public List<HotNewsSectionRequest> extractSections(String rawMarkdown) {
        List<HotNewsSectionRequest> sections = new ArrayList<>();
        if (!StringUtils.hasText(rawMarkdown)) {
            return sections;
        }

        String currentSection = null;
        List<HotNewsItemRequest> currentItems = new ArrayList<>();
        boolean inCodeFence = false;

        for (String line : rawMarkdown.split("\\R")) {
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence;
                continue;
            }
            if (inCodeFence) {
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flush(sections, currentSection, currentItems);
                currentSection = stripInlineMarkup(heading.group(2));
                currentItems = new ArrayList<>();
                continue;
            }

            Matcher listItem = LIST_ITEM.matcher(line);
            if (listItem.matches() && currentItems.size() < MAX_ITEMS_PER_SECTION) {
                HotNewsItemRequest item = toItem(listItem.group(1), currentItems.size() + 1);
                if (item != null) {
                    currentItems.add(item);
                }
            }
        }
        flush(sections, currentSection, currentItems);

        if (sections.size() > MAX_SECTIONS) {
            log.warn("Daily report markdown produced {} sections, keeping the first {}",
                    sections.size(), MAX_SECTIONS);
            return new ArrayList<>(sections.subList(0, MAX_SECTIONS));
        }
        return sections;
    }

    private void flush(List<HotNewsSectionRequest> sections, String sectionName,
                       List<HotNewsItemRequest> items) {
        if (items.isEmpty()) {
            return;
        }
        HotNewsSectionRequest section = new HotNewsSectionRequest();
        section.setSection(StringUtils.hasText(sectionName) ? sectionName : "HIGHLIGHTS");
        section.setItems(new ArrayList<>(items));
        sections.add(section);
    }

    private HotNewsItemRequest toItem(String text, int rank) {
        String content = text == null ? "" : text.trim();
        if (content.isEmpty()) {
            return null;
        }

        String url = null;
        Matcher link = MARKDOWN_LINK.matcher(content);
        if (link.find()) {
            url = link.group(2);
            content = link.replaceAll("$1");
        } else {
            Matcher bare = BARE_URL.matcher(content);
            if (bare.find()) {
                url = bare.group(1);
                content = bare.replaceAll("").trim();
            }
        }

        Integer score = null;
        Matcher scoreMatcher = SCORE.matcher(content);
        if (scoreMatcher.find()) {
            String value = scoreMatcher.group(1) != null ? scoreMatcher.group(1) : scoreMatcher.group(2);
            try {
                score = Integer.parseInt(value);
                content = scoreMatcher.replaceAll("").trim();
            } catch (NumberFormatException ignored) {
                // annotation was not a number after all; keep the text as-is
            }
        }

        String title = stripInlineMarkup(content);
        if (title.isEmpty()) {
            return null;
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH);
        }

        HotNewsItemRequest item = new HotNewsItemRequest();
        item.setTitle(title);
        item.setUrl(url);
        item.setScore(score);
        item.setRank(rank);
        return item;
    }

    /**
     * Remove the inline markers that would otherwise show up as literal characters
     * in a plain-text title or summary.
     */
    private String stripInlineMarkup(String text) {
        return text
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("^\\s*[-*+]\\s*", "")
                .trim();
    }
}
