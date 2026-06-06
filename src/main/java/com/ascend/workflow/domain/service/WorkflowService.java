package com.ascend.workflow.domain.service;

import com.ascend.workflow.api.dto.CreateWorkflowRequest;
import com.ascend.workflow.api.dto.StepConditionDto;
import com.ascend.workflow.api.dto.WorkflowStepDto;
import com.ascend.workflow.domain.model.StepCondition;
import com.ascend.workflow.domain.model.WorkflowStep;
import com.ascend.workflow.domain.model.WorkflowTemplate;
import com.ascend.workflow.infrastructure.repository.StepConditionRepository;
import com.ascend.workflow.infrastructure.repository.WorkflowStepRepository;
import com.ascend.workflow.infrastructure.repository.WorkflowTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowTemplateRepository templateRepository;
    private final WorkflowStepRepository stepRepository;
    private final StepConditionRepository conditionRepository;

    public Mono<WorkflowTemplate> create(CreateWorkflowRequest request, UUID createdBy) {
        WorkflowTemplate template = WorkflowTemplate.builder()
                .name(request.name())
                .description(request.description())
                .isActive(true)
                .createdBy(createdBy)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        return templateRepository.save(template)
                .flatMap(saved -> saveSteps(saved.getId(), request.steps()).then(Mono.just(saved)));
    }

    private Mono<Void> saveSteps(UUID templateId, java.util.List<WorkflowStepDto> stepDtos) {
        return Flux.fromIterable(stepDtos)
                .flatMap(dto -> {
                    WorkflowStep step = WorkflowStep.builder()
                            .templateId(templateId)
                            .stepOrder(dto.stepOrder())
                            .parallelGroup(dto.parallelGroup())
                            .name(dto.name())
                            .approverType(dto.approverType())
                            .approverId(dto.approverId())
                            .approvalMode(dto.approvalMode())
                            .timeoutHours(dto.timeoutHours())
                            .escalationUserId(dto.escalationUserId())
                            .createdAt(OffsetDateTime.now())
                            .build();
                    return stepRepository.save(step)
                            .flatMap(savedStep -> saveConditions(savedStep.getId(), dto.conditions()));
                })
                .then();
    }

    private Mono<Void> saveConditions(UUID stepId, java.util.List<StepConditionDto> conditionDtos) {
        if (conditionDtos == null || conditionDtos.isEmpty()) return Mono.empty();
        return Flux.fromIterable(conditionDtos)
                .flatMap(dto -> conditionRepository.save(StepCondition.builder()
                        .stepId(stepId)
                        .fieldName(dto.fieldName())
                        .operator(dto.operator())
                        .value(dto.value())
                        .build()))
                .then();
    }

    public Flux<WorkflowTemplate> findAll(Pageable pageable) {
        return templateRepository.findAllBy(pageable);
    }

    public Mono<Long> count() {
        return templateRepository.count();
    }

    public Mono<WorkflowTemplate> findById(UUID id) {
        return templateRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Workflow not found: " + id)));
    }

    public Mono<WorkflowTemplate> update(UUID id, CreateWorkflowRequest request, UUID userId) {
        return findById(id)
                .flatMap(template -> {
                    template.setName(request.name());
                    template.setDescription(request.description());
                    template.setUpdatedAt(OffsetDateTime.now());
                    return templateRepository.save(template)
                            .flatMap(saved -> stepRepository.findByTemplateIdOrderByStepOrderAsc(id)
                                    .flatMap(step -> conditionRepository.findByStepId(step.getId())
                                            .flatMap(conditionRepository::delete).then(stepRepository.delete(step)))
                                    .then(saveSteps(id, request.steps()))
                                    .then(Mono.just(saved)));
                });
    }

    public Mono<Void> delete(UUID id) {
        return findById(id)
                .flatMap(template -> {
                    template.setActive(false);
                    template.setUpdatedAt(OffsetDateTime.now());
                    return templateRepository.save(template).then();
                });
    }

    public Flux<WorkflowStep> findStepsByTemplateId(UUID templateId) {
        return stepRepository.findByTemplateIdOrderByStepOrderAsc(templateId);
    }

    public Flux<StepCondition> findConditionsByStepId(UUID stepId) {
        return conditionRepository.findByStepId(stepId);
    }
}
