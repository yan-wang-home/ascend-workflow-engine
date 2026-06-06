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
@Table("instance_steps")
public class InstanceStep {

    @Id
    private UUID id;
    private UUID instanceId;
    private UUID stepId;
    private int stepOrder;
    private Integer parallelGroup;
    private String status;  // PENDING | APPROVED | REJECTED | SKIPPED | ESCALATED
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime escalatedAt;
    private UUID escalatedToUserId;
}
