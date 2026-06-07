package com.ascend.workflow.domain.service;

import com.ascend.workflow.api.dto.CreateDelegationRequest;
import com.ascend.workflow.domain.model.Delegation;
import com.ascend.workflow.infrastructure.repository.DelegationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DelegationService {

    private final DelegationRepository delegationRepository;

    public Mono<Delegation> create(CreateDelegationRequest request, UUID delegatorId) {
        if (request.delegateId().equals(delegatorId)) {
            return Mono.error(new IllegalArgumentException("Cannot delegate to yourself"));
        }
        if (!request.endsAt().isAfter(request.startsAt())) {
            return Mono.error(new IllegalArgumentException("End date must be after start date"));
        }

        Delegation delegation = Delegation.builder()
                .delegatorId(delegatorId)
                .delegateId(request.delegateId())
                .templateId(request.templateId())
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
                .isActive(true)
                .createdAt(OffsetDateTime.now())
                .build();
        return delegationRepository.save(delegation);
    }

    public Flux<Delegation> findByDelegator(UUID delegatorId, Pageable pageable) {
        return delegationRepository.findByDelegatorIdOrderByCreatedAtDesc(delegatorId, pageable);
    }

    public Mono<Long> countByDelegator(UUID delegatorId) {
        return delegationRepository.countByDelegatorId(delegatorId);
    }

    public Mono<Void> revoke(UUID delegationId, UUID delegatorId) {
        return delegationRepository.findById(delegationId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Delegation not found")))
                .flatMap(delegation -> {
                    if (!delegation.getDelegatorId().equals(delegatorId)) {
                        return Mono.error(new SecurityException("Not authorized to revoke this delegation"));
                    }
                    return delegationRepository.deleteById(delegationId);
                });
    }
}
