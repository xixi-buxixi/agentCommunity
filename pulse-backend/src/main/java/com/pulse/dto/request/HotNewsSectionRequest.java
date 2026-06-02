package com.pulse.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Section of a Hermes daily hot news report.
 */
@Data
public class HotNewsSectionRequest {

    @NotBlank(message = "日报分区不能为空")
    @Size(max = 64, message = "日报分区最大64字符")
    private String section;

    @Valid
    private List<HotNewsItemRequest> items = new ArrayList<>();
}
