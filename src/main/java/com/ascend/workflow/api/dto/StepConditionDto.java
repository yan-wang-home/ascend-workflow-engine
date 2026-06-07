package com.ascend.workflow.api.dto;

import com.ascend.workflow.domain.model.ConditionOperator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StepConditionDto(
        @NotBlank String fieldName,
        @NotNull ConditionOperator operator,
        @NotBlank String value
) {}
