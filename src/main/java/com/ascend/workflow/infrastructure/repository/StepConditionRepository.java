package com.ascend.workflow.infrastructure.repository;

import com.ascend.workflow.domain.model.StepCondition;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface StepConditionRepository extends ReactiveCrudRepository<StepCondition, UUID> {

    Flux<StepCondition> findByStepId(UUID stepId);
}
