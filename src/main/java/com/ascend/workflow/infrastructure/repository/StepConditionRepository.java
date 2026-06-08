package com.ascend.workflow.infrastructure.repository;

import com.ascend.workflow.domain.model.StepCondition;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

public interface StepConditionRepository extends ReactiveCrudRepository<StepCondition, UUID> {

    Flux<StepCondition> findByStepId(UUID stepId);

    Flux<StepCondition> findByStepIdIn(Collection<UUID> stepIds);

    @Modifying
    @Query("DELETE FROM step_conditions WHERE step_id IN (:stepIds)")
    Mono<Void> deleteByStepIdIn(Collection<UUID> stepIds);
}
