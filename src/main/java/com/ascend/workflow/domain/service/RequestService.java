package com.ascend.workflow.domain.service;

import com.ascend.workflow.api.dto.ResubmitRequestDto;
import com.ascend.workflow.api.dto.SubmitRequestDto;
import com.ascend.workflow.domain.model.InstanceStep;
import com.ascend.workflow.domain.model.InstanceStepStatus;
import com.ascend.workflow.domain.model.StepCondition;
import com.ascend.workflow.domain.model.WorkflowInstance;
import com.ascend.workflow.domain.model.WorkflowInstanceStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
                            .status(WorkflowInstanceStatus.PENDING)
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
                .collectList()
                .flatMap(steps -> {
                    List<UUID> stepIds = steps.stream().map(WorkflowStep::getId).collect(Collectors.toList());
                    return conditionRepository.findByStepIdIn(stepIds)
                            .collectMultimap(StepCondition::getStepId)
                            .flatMap(conditionsByStepId -> Flux.fromIterable(steps)
                                    .flatMap(step -> evaluateAndCreateStep(instance, step, metadata,
                                            new ArrayList<>(conditionsByStepId.getOrDefault(step.getId(), List.of()))))
                                    .then());
                });
    }

    private Mono<InstanceStep> evaluateAndCreateStep(WorkflowInstance instance, WorkflowStep step,
                                                      Map<String, Object> metadata,
                                                      List<StepCondition> conditions) {
        return Mono.defer(() -> {
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

                    InstanceStepStatus status = conditionsMet ? InstanceStepStatus.PENDING : InstanceStepStatus.SKIPPED;
                    OffsetDateTime startedAt = (conditionsMet && step.getStepOrder() == instance.getCurrentStepOrder())
                            ? OffsetDateTime.now() : null;

                    InstanceStep instanceStep = InstanceStep.builder()
                            .instanceId(instance.getId())
                            .stepId(step.getId())
                            .name(step.getName())
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
                    if (instance.getStatus() != WorkflowInstanceStatus.PENDING) {
                        return Mono.error(new IllegalStateException("Only PENDING requests can be cancelled"));
                    }
                    instance.setStatus(WorkflowInstanceStatus.CANCELLED);
                    instance.setUpdatedAt(OffsetDateTime.now());
                    return instanceRepository.save(instance)
                            .flatMap(saved -> auditService.log(id, requesterId, "REQUEST_CANCELLED", Map.of())
                                    .thenReturn(saved));
                });
    }

    public Mono<WorkflowInstance> resubmit(UUID instanceId, UUID requesterId, ResubmitRequestDto dto) {
        return findById(instanceId)
                .flatMap(instance -> {
                    if (!instance.getRequesterId().equals(requesterId)) {
                        return Mono.error(new SecurityException("Not authorized to resubmit this request"));
                    }
                    if (instance.getStatus() != WorkflowInstanceStatus.CHANGES_REQUESTED) {
                        return Mono.error(new IllegalStateException(
                                "Only CHANGES_REQUESTED requests can be resubmitted"));
                    }
                    if (dto.title() != null) {
                        instance.setTitle(dto.title());
                    }
                    return updateMetadataIfProvided(instance, dto.metadata())
                            .flatMap(updated -> {
                                updated.setStatus(WorkflowInstanceStatus.PENDING);
                                updated.setUpdatedAt(OffsetDateTime.now());
                                return instanceRepository.save(updated);
                            })
                            .flatMap(saved -> auditService.log(instanceId, requesterId, "REQUEST_RESUBMITTED",
                                    Map.of("title", saved.getTitle())).thenReturn(saved));
                });
    }

    private Mono<WorkflowInstance> updateMetadataIfProvided(WorkflowInstance instance, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Mono.just(instance);
        }
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(metadata))
                .map(json -> {
                    instance.setMetadata(Json.of(json));
                    return instance;
                });
    }

    public Flux<InstanceStep> findStepsByInstanceId(UUID instanceId) {
        return instanceStepRepository.findByInstanceIdOrderByStepOrderAsc(instanceId);
    }
}
