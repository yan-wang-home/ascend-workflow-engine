package com.ascend.workflow.api;

import com.ascend.workflow.api.dto.PageResponse;
import com.ascend.workflow.api.dto.ResubmitRequestDto;
import com.ascend.workflow.api.dto.SubmitRequestDto;
import com.ascend.workflow.domain.model.AuditTrail;
import com.ascend.workflow.domain.model.Decision;
import com.ascend.workflow.domain.model.InstanceStep;
import com.ascend.workflow.domain.model.WorkflowInstance;
import com.ascend.workflow.domain.service.RequestService;
import com.ascend.workflow.infrastructure.repository.AuditTrailRepository;
import com.ascend.workflow.infrastructure.repository.DecisionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/requests")
@RequiredArgsConstructor
@Tag(name = "Requests", description = "Approval request submission and management")
@SecurityRequirement(name = "bearerAuth")
public class RequestController {

    private final RequestService requestService;
    private final AuditTrailRepository auditTrailRepository;
    private final DecisionRepository decisionRepository;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit a new approval request")
    public Mono<WorkflowInstance> submit(@Valid @RequestBody SubmitRequestDto request, Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return requestService.submit(request, userId);
    }

    @GetMapping
    @Operation(summary = "List my submitted requests (paginated)")
    public Mono<PageResponse<WorkflowInstance>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") @Max(200) int size,
            Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return requestService.findByRequester(userId, pageable).collectList()
                .zipWith(requestService.countByRequester(userId))
                .map(t -> PageResponse.of(t.getT1(), page, size, t.getT2()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get request details including steps and their decisions")
    public Mono<WorkflowInstanceDetail> getById(@PathVariable UUID id, Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return requestService.findById(id)
                .flatMap(instance -> {
                    boolean isAdmin = auth.getAuthorities().stream()
                            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
                    if (!isAdmin && !instance.getRequesterId().equals(userId)) {
                        return Mono.error(new SecurityException("Not authorized to view this request"));
                    }
                    return requestService.findStepsByInstanceId(id)
                            .flatMap(step -> decisionRepository.findByInstanceStepId(step.getId()).collectList()
                                    .map(decisions -> new StepWithDecisions(step, decisions)))
                            .collectList()
                            .map(steps -> new WorkflowInstanceDetail(instance, steps));
                });
    }

    @GetMapping("/{id}/audit")
    @Operation(summary = "Get full audit trail for a request")
    public Mono<PageResponse<AuditTrail>> getAudit(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") @Max(200) int size) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").ascending());
        return auditTrailRepository.findByInstanceIdOrderByCreatedAtAsc(id, pageable).collectList()
                .zipWith(auditTrailRepository.countByInstanceId(id))
                .map(t -> PageResponse.of(t.getT1(), page, size, t.getT2()));
    }

    @PostMapping("/{id}/resubmit")
    @Operation(summary = "Resubmit a request after changes were requested by an approver")
    public Mono<WorkflowInstance> resubmit(@PathVariable UUID id,
                                            @RequestBody(required = false) ResubmitRequestDto dto,
                                            Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return requestService.resubmit(id, userId, dto != null ? dto : new ResubmitRequestDto(null, null));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancel a pending request")
    public Mono<Void> cancel(@PathVariable UUID id, Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return requestService.cancel(id, userId).then();
    }

    public record StepWithDecisions(InstanceStep step, List<Decision> decisions) {}
    public record WorkflowInstanceDetail(WorkflowInstance instance, List<StepWithDecisions> steps) {}
}
