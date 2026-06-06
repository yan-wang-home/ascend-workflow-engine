package com.ascend.workflow.domain.service;

import com.ascend.workflow.api.dto.SubmitRequestDto;
import com.ascend.workflow.domain.model.InstanceStep;
import com.ascend.workflow.domain.model.WorkflowInstance;
import com.ascend.workflow.domain.model.WorkflowStep;
import com.ascend.workflow.infrastructure.repository.InstanceStepRepository;
import com.ascend.workflow.infrastructure.repository.StepConditionRepository;
import com.ascend.workflow.infrastructure.repository.WorkflowInstanceRepository;
import com.ascend.workflow.infrastructure.repository.WorkflowStepRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RequestService {

    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowStepRepository stepRepository;
    private final StepConditionRepository conditionRepository;
    private final InstanceStepRepository instanceStepRepository;
    private final ConditionEvaluator conditionEvaluator;
    private final AuditService auditService;
    private final EscalationService escalationService;
    private final ObjectMapper objectMapper;

    public Mono<WorkflowInstance> submit(SubmitRequestDto request, UUID requesterId) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(request.metadata()))
                .flatMap(metadataJson -> {
                    WorkflowInstance instance = WorkflowInstance.builder()
                            .templateId(request.templateId())
                            .requesterId(requesterId)
                            .title(request.title())
                            .status("PENDING")
                            .metadata(Json.of(metadataJson))
                            .currentStepOrder(1)
                            .createdAt(OffsetDateTime.now())
                            .updatedAt(OffsetDateTime.now())
                            .build();
                    return instanceRepository.save(instance);
                })
                .flatMap(instance -> instantiateSteps(instance, request.metadata())
                        .then(auditService.log(instance.getId(), requesterId, "REQUEST_SUBMITTED",
                                Map.of("templateId", request.templateId(), "title", request.title())))
                        .thenReturn(instance));
    }

    private Mono<Void> instantiateSteps(WorkflowInstance instance, Map<String, Object> metadata) {
        return stepRepository.findByTemplateIdOrderByStepOrderAsc(instance.getTemplateId())
                .flatMap(step -> evaluateAndCreateStep(instance, step, metadata))
                .then();
    }

    private Mono<InstanceStep> evaluateAndCreateStep(WorkflowInstance instance, WorkflowStep step,
                                                      Map<String, Object> metadata) {
        return conditionRepository.findByStepId(step.getId())
                .collectList()
                .flatMap(conditions -> {
                    boolean conditionsMet;
                    if (conditions.isEmpty()) {
                        conditionsMet = true;
                    } else {
                        try {
                            JsonNode metadataNode = objectMapper.valueToTree(metadata);
                            conditionsMet = conditionEvaluator.evaluate(conditions, metadataNode);
                        } catch (Exception e) {
                            log.warn("Condition evaluation failed for step {}: {}", step.getId(), e.getMessage());
                            conditionsMet = false;
                        }
                    }

                    String status = conditionsMet ? "PENDING" : "SKIPPED";
                    OffsetDateTime startedAt = (conditionsMet && step.getStepOrder() == instance.getCurrentStepOrder())
                            ? OffsetDateTime.now() : null;

                    InstanceStep instanceStep = InstanceStep.builder()
                            .instanceId(instance.getId())
                            .stepId(step.getId())
                            .stepOrder(step.getStepOrder())
                            .parallelGroup(step.getParallelGroup())
                            .status(status)
                            .startedAt(startedAt)
                            .build();
                    return instanceStepRepository.save(instanceStep)
                            .doOnSuccess(saved -> {
                                if (saved.getStartedAt() != null) {
                                    escalationService.scheduleEscalation(saved, step);
                                }
                            });
                });
    }

    public Flux<WorkflowInstance> findByRequester(UUID requesterId, Pageable pageable) {
        return instanceRepository.findByRequesterIdOrderByCreatedAtDesc(requesterId, pageable);
    }

    public Mono<Long> countByRequester(UUID requesterId) {
        return instanceRepository.countByRequesterId(requesterId);
    }

    public Mono<WorkflowInstance> findById(UUID id) {
        return instanceRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Request not found: " + id)));
    }

    public Mono<WorkflowInstance> cancel(UUID id, UUID requesterId) {
        return findById(id)
                .flatMap(instance -> {
                    if (!instance.getRequesterId().equals(requesterId)) {
                        return Mono.error(new SecurityException("Not authorized to cancel this request"));
                    }
                    if (!instance.getStatus().equals("PENDING")) {
                        return Mono.error(new IllegalStateException("Only PENDING requests can be cancelled"));
                    }
                    instance.setStatus("CANCELLED");
                    instance.setUpdatedAt(OffsetDateTime.now());
                    return instanceRepository.save(instance)
                            .flatMap(saved -> auditService.log(id, requesterId, "REQUEST_CANCELLED", Map.of())
                                    .thenReturn(saved));
                });
    }

    public Flux<InstanceStep> findStepsByInstanceId(UUID instanceId) {
        return instanceStepRepository.findByInstanceIdOrderByStepOrderAsc(instanceId);
    }
}
