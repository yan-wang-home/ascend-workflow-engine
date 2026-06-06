package com.ascend.workflow.api.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CreateDelegationRequest(
        @NotNull UUID delegateId,
        UUID templateId,
        @NotNull OffsetDateTime startsAt,
        @NotNull @Future OffsetDateTime endsAt
) {}
