package com.ascend.workflow.infrastructure.repository;

import com.ascend.workflow.domain.model.WorkflowStep;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface WorkflowStepRepository extends ReactiveCrudRepository<WorkflowStep, UUID> {

    Flux<WorkflowStep> findByTemplateIdOrderByStepOrderAsc(UUID templateId);

    Flux<WorkflowStep> findByTemplateIdAndStepOrderOrderByParallelGroupAsc(UUID templateId, int stepOrder);
}
