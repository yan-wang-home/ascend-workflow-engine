package com.ascend.workflow.infrastructure.repository;

import com.ascend.workflow.domain.model.WorkflowTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface WorkflowTemplateRepository extends ReactiveCrudRepository<WorkflowTemplate, UUID> {

    Flux<WorkflowTemplate> findAllBy(Pageable pageable);

    Flux<WorkflowTemplate> findByIsActiveTrue(Pageable pageable);

    Mono<Long> countByIsActiveTrue();
}
