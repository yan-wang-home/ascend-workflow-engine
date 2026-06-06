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
@Table("workflow_instances")
public class WorkflowInstance {

    @Id
    private UUID id;
    private UUID templateId;
    private UUID requesterId;
    private String title;
    private String status;  // PENDING | APPROVED | REJECTED | CANCELLED
    private Json metadata;
    private int currentStepOrder;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
