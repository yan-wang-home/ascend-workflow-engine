package com.ascend.workflow.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record ChatRequest(
        UUID sessionId,
        @NotBlank String message
) {}
