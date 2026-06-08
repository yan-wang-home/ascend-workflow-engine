package com.ascend.workflow.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record SubmitRequestDto(
        @NotNull UUID templateId,
        @NotBlank @Size(max = 255) String title,
        @NotNull Map<String, Object> metadata
) {}
