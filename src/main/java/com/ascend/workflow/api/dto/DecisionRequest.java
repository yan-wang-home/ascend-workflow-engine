package com.ascend.workflow.api.dto;

import com.ascend.workflow.domain.model.DecisionAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DecisionRequest(
        @NotNull DecisionAction action,
        @Size(max = 2000) String comment
) {}
