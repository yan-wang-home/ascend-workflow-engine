package com.ascend.workflow.infrastructure.repository;

import com.ascend.workflow.domain.model.AuditTrail;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface AuditTrailRepository extends ReactiveCrudRepository<AuditTrail, UUID> {

    Flux<AuditTrail> findByInstanceIdOrderByCreatedAtAsc(UUID instanceId, Pageable pageable);

    Mono<Long> countByInstanceId(UUID instanceId);
}
