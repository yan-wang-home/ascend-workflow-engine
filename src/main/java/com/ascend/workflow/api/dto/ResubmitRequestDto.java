package com.ascend.workflow.api.dto;

import java.util.Map;

public record ResubmitRequestDto(
        String title,
        Map<String, Object> metadata
) {}
