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
@Table("workflow_steps")
public class WorkflowStep {

    @Id
    private UUID id;
    private UUID templateId;
    private int stepOrder;
    private Integer parallelGroup;
    private String name;
    private String approverType;   // USER | GROUP | ROLE
    private String approverId;
    private String approvalMode;   // ANY_OF | ALL_OF
    private Integer timeoutHours;
    private UUID escalationUserId;
    private OffsetDateTime createdAt;
}
