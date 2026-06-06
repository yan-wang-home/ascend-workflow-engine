package com.ascend.workflow.infrastructure.repository;

import com.ascend.workflow.domain.model.Decision;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface DecisionRepository extends ReactiveCrudRepository<Decision, UUID> {

    Flux<Decision> findByInstanceStepId(UUID instanceStepId);

    Mono<Boolean> existsByInstanceStepIdAndApproverId(UUID instanceStepId, UUID approverId);
}
