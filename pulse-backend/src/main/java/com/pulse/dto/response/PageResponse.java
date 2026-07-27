package com.pulse.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Paginated Response Wrapper
 *
 * Used for list endpoints with pagination support.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    /**
     * List of items
     */
    private List<T> list;

    /**
     * Total number of items
     */
    private long total;

    /**
     * Current page number
     */
    private int page;

    /**
     * Page size
     */
    private int size;

    /**
     * Create from any MyBatis Plus page (Page or IPage).
     *
     * Every list endpoint must return this shape. Returning the MyBatis Page object
     * directly leaked implementation details (pages, orders, optimizeCountSql,
     * searchCount) and used different field names (records/current) from the rest of
     * the API, so the frontend needed a separate unwrapping rule per endpoint.
     */
    public static <T> PageResponse<T> from(com.baomidou.mybatisplus.core.metadata.IPage<T> page) {
        return PageResponse.<T>builder()
                .list(page.getRecords())
                .total(page.getTotal())
                .page((int) page.getCurrent())
                .size((int) page.getSize())
                .build();
    }
}