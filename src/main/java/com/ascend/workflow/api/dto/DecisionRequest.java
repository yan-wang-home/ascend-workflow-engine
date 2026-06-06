package com.ascend.workflow.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DecisionRequest(
        @NotBlank @Pattern(regexp = "APPROVE|REJECT|REQUEST_CHANGES") String action,
        String comment
) {}
