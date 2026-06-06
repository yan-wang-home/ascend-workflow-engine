package com.ascend.workflow.domain.model;

import io.r2dbc.postgresql.codec.Json;
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
@Table("audit_trail")
public class AuditTrail {

    @Id
    private UUID id;
    private UUID instanceId;
    private UUID userId;
    private String action;
    private Json details;
    private OffsetDateTime createdAt;
}
