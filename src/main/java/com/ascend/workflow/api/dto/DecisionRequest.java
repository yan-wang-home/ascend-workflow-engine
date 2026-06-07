package com.ascend.workflow.api.dto;

import com.ascend.workflow.domain.model.DecisionAction;
import jakarta.validation.constraints.NotNull;

public record DecisionRequest(
        @NotNull DecisionAction action,
        String comment
) {}
