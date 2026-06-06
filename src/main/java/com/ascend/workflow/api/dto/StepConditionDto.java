package com.ascend.workflow.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record StepConditionDto(
        @NotBlank String fieldName,
        @NotBlank @Pattern(regexp = "EQ|NEQ|GT|GTE|LT|LTE|IN|CONTAINS") String operator,
        @NotBlank String value
) {}
