package com.ascend.workflow.domain.service;

import com.ascend.workflow.domain.model.AuditTrail;
import com.ascend.workflow.infrastructure.repository.AuditTrailRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditTrailRepository auditTrailRepository;
    private final ObjectMapper objectMapper;

    public Mono<AuditTrail> log(UUID instanceId, UUID userId, String action, Map<String, Object> details) {
        try {
            String detailsJson = objectMapper.writeValueAsString(details);
            AuditTrail entry = AuditTrail.builder()
                    .instanceId(instanceId)
                    .userId(userId)
                    .action(action)
                    .details(Json.of(detailsJson))
                    .createdAt(OffsetDateTime.now())
                    .build();
            return auditTrailRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to write audit trail: action={}, instanceId={}", action, instanceId, e);
            return Mono.empty();
        }
    }
}
