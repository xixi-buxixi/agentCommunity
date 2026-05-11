package com.pulse.scheduler;

import com.pulse.entity.FrontierNewsSource;
import com.pulse.service.FrontierNewsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight frontier news scheduler.
 *
 * First version accepts configured title|url|content seeds so the publishing,
 * dedupe, compression and tagging pipeline is testable without a crawler stack.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FrontierNewsScheduler {

    private final FrontierNewsService frontierNewsService;

    @Value("${scheduler.frontier-news.enabled:false}")
    private boolean enabled;

    @Value("#{'${scheduler.frontier-news.sources:}'.split(';;')}")
    private List<String> configuredSources;

    @Scheduled(fixedRateString = "${scheduler.frontier-news.interval:21600000}")
    public void crawlAndPublish() {
        if (!enabled) {
            log.debug("Frontier news scheduler is disabled");
            return;
        }
        List<FrontierNewsSource> items = parseConfiguredSources();
        if (items.isEmpty()) {
            log.debug("No frontier news sources configured");
            return;
        }
        frontierNewsService.publishFetchedItems(items);
    }

    private List<FrontierNewsSource> parseConfiguredSources() {
        List<FrontierNewsSource> items = new ArrayList<>();
        for (String source : configuredSources) {
            if (source == null || source.isBlank()) {
                continue;
            }
            String[] parts = source.split("\\|", 3);
            if (parts.length < 2) {
                continue;
            }
            FrontierNewsSource item = new FrontierNewsSource();
            item.setSourceTitle(parts[0].trim());
            item.setSourceUrl(parts[1].trim());
            item.setRawContent(parts.length == 3 ? parts[2].trim() : parts[0].trim());
            item.setSourcePublishedAt(LocalDateTime.now());
            items.add(item);
        }
        return items;
    }
}
