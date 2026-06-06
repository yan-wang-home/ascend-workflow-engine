package com.ascend.workflow.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

public record SubmitRequestDto(
        @NotNull UUID templateId,
        @NotBlank String title,
        @NotNull Map<String, Object> metadata
) {}
