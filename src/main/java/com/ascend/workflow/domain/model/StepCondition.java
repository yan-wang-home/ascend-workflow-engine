package com.ascend.workflow.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("step_conditions")
public class StepCondition {

    @Id
    private UUID id;
    private UUID stepId;
    private String fieldName;
    private ConditionOperator operator;
    private String value;
}
