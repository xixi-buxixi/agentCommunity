package com.pulse.service;

import com.pulse.dto.request.HotNewsIngestRequest;
import com.pulse.dto.response.HotNewsReportResponse;

/**
 * Daily hot news report service.
 */
public interface HotNewsService {

    HotNewsReportResponse ingest(HotNewsIngestRequest request, String token);

    HotNewsReportResponse getLatest();

    HotNewsReportResponse getDetail(Long reportId);
}
