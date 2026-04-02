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
     * Create from MyBatis Plus Page object
     */
    public static <T> PageResponse<T> from(com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> page) {
        return PageResponse.<T>builder()
                .list(page.getRecords())
                .total(page.getTotal())
                .page((int) page.getCurrent())
                .size((int) page.getSize())
                .build();
    }
}