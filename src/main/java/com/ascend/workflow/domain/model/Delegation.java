package com.ascend.workflow.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("delegations")
public class Delegation {

    @Id
    private UUID id;
    private UUID delegatorId;
    private UUID delegateId;
    private UUID templateId;  // null = all templates
    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;
    private boolean isActive;
    private OffsetDateTime createdAt;
}
