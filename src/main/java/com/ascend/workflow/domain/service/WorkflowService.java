package com.ascend.workflow.domain.service;

import com.ascend.workflow.api.dto.CreateWorkflowRequest;
import com.ascend.workflow.api.dto.StepConditionDto;
import com.ascend.workflow.api.dto.WorkflowStepDto;
import com.ascend.workflow.domain.model.ApproverType;
import com.ascend.workflow.domain.model.StepCondition;
import com.ascend.workflow.domain.model.WorkflowStep;
import com.ascend.workflow.domain.model.WorkflowTemplate;
import com.ascend.workflow.infrastructure.repository.StepConditionRepository;
import com.ascend.workflow.infrastructure.repository.UserGroupRepository;
import com.ascend.workflow.infrastructure.repository.UserRepository;
import com.ascend.workflow.infrastructure.repository.WorkflowInstanceRepository;
import com.ascend.workflow.infrastructure.repository.WorkflowStepRepository;
import com.ascend.workflow.infrastructure.repository.WorkflowTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowTemplateRepository templateRepository;
    private final WorkflowStepRepository stepRepository;
    private final StepConditionRepository conditionRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final UserRepository userRepository;
    private final UserGroupRepository userGroupRepository;

    private Mono<Void> validateSteps(java.util.List<WorkflowStepDto> steps) {
        return Flux.fromIterable(steps)
                .flatMap(step -> {
                    if (step.approverType() == ApproverType.ROLE) return Mono.empty();
                    UUID id;
                    try {
                        id = UUID.fromString(step.approverId());
                    } catch (IllegalArgumentException e) {
                        return Mono.error(new IllegalArgumentException(
                                "Step '" + step.name() + "': approverId must be a valid UUID when approverType is "
                                        + step.approverType()));
                    }
                    return switch (step.approverType()) {
                        case USER -> userRepository.existsById(id)
                                .flatMap(exists -> exists ? Mono.empty()
                                        : Mono.error(new IllegalArgumentException(
                                                "Step '" + step.name() + "': user " + id + " does not exist")));
                        case GROUP -> userGroupRepository.existsById(id)
                                .flatMap(exists -> exists ? Mono.empty()
                                        : Mono.error(new IllegalArgumentException(
                                                "Step '" + step.name() + "': group " + id + " does not exist")));
                        case ROLE -> Mono.empty();
                    };
                })
                .then();
    }

    public Mono<WorkflowTemplate> create(CreateWorkflowRequest request, UUID createdBy) {
        WorkflowTemplate template = WorkflowTemplate.builder()
                .name(request.name())
                .description(request.description())
                .isActive(true)
                .createdBy(createdBy)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        return validateSteps(request.steps())
                .then(templateRepository.save(template))
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
        return validateSteps(request.steps())
                .then(findById(id))
                .flatMap(template -> {

                    template.setName(request.name());
                    template.setDescription(request.description());
                    template.setUpdatedAt(OffsetDateTime.now());
                    return templateRepository.save(template)
                            .flatMap(saved -> stepRepository.findByTemplateIdOrderByStepOrderAsc(id)
                                    .map(step -> step.getId()).collectList()
                                    .flatMap(stepIds -> conditionRepository.deleteByStepIdIn(stepIds)
                                            .then(Flux.fromIterable(stepIds)
                                                    .flatMap(stepRepository::deleteById).then()))
                                    .then(saveSteps(id, request.steps()))
                                    .then(Mono.just(saved)));
                });
    }

    public Mono<Void> delete(UUID id) {
        return findById(id)
                .flatMap(template -> instanceRepository.countByTemplateId(id)
                        .flatMap(count -> {
                            if (count == 0) {
                                // No instances — hard delete steps, conditions, and template
                                return stepRepository.findByTemplateIdOrderByStepOrderAsc(id)
                                        .map(step -> step.getId()).collectList()
                                        .flatMap(stepIds -> conditionRepository.deleteByStepIdIn(stepIds)
                                                .then(Flux.fromIterable(stepIds)
                                                        .flatMap(stepRepository::deleteById).then()))
                                        .then(templateRepository.delete(template));
                            } else {
                                // Instances exist — soft delete to preserve history
                                template.setActive(false);
                                template.setUpdatedAt(OffsetDateTime.now());
                                return templateRepository.save(template).then();
                            }
                        }));
    }

    public Flux<WorkflowStep> findStepsByTemplateId(UUID templateId) {
        return stepRepository.findByTemplateIdOrderByStepOrderAsc(templateId);
    }

    public Flux<StepCondition> findConditionsByStepId(UUID stepId) {
        return conditionRepository.findByStepId(stepId);
    }
}
