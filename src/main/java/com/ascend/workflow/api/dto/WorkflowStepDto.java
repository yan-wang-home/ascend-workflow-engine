package com.ascend.workflow.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

public record WorkflowStepDto(
        @Min(1) int stepOrder,
        Integer parallelGroup,
        @NotBlank String name,
        @NotBlank @Pattern(regexp = "USER|GROUP|ROLE") String approverType,
        @NotBlank String approverId,
        @NotNull @Pattern(regexp = "ANY_OF|ALL_OF") String approvalMode,
        Integer timeoutHours,
        UUID escalationUserId,
        List<StepConditionDto> conditions
) {}
