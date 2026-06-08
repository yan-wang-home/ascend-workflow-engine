package com.ascend.workflow.api.dto;

import com.ascend.workflow.domain.model.ApprovalMode;
import com.ascend.workflow.domain.model.ApproverType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record WorkflowStepDto(
        @Min(1) int stepOrder,
        Integer parallelGroup,
        @NotBlank String name,
        @NotNull ApproverType approverType,
        @NotBlank String approverId,
        ApprovalMode approvalMode,
        Integer timeoutHours,
        UUID escalationUserId,
        List<StepConditionDto> conditions
) {}
