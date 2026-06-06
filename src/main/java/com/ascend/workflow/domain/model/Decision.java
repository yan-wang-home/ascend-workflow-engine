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
@Table("decisions")
public class Decision {

    @Id
    private UUID id;
    private UUID instanceStepId;
    private UUID approverId;
    private String action;  // APPROVE | REJECT | REQUEST_CHANGES
    private String comment;
    private OffsetDateTime decidedAt;
}
