package com.ascend.workflow.api;

import com.ascend.workflow.api.dto.PageResponse;
import com.ascend.workflow.api.dto.SubmitRequestDto;
import com.ascend.workflow.domain.model.AuditTrail;
import com.ascend.workflow.domain.model.InstanceStep;
import com.ascend.workflow.domain.model.WorkflowInstance;
import com.ascend.workflow.domain.service.RequestService;
import com.ascend.workflow.infrastructure.repository.AuditTrailRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requests")
@RequiredArgsConstructor
@Tag(name = "Requests", description = "Approval request submission and management")
@SecurityRequirement(name = "bearerAuth")
public class RequestController {

    private final RequestService requestService;
    private final AuditTrailRepository auditTrailRepository;

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
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return requestService.findByRequester(userId, pageable).collectList()
                .zipWith(requestService.countByRequester(userId))
                .map(t -> PageResponse.of(t.getT1(), page, size, t.getT2()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get request details including steps")
    public Mono<WorkflowInstanceDetail> getById(@PathVariable UUID id) {
        return requestService.findById(id)
                .flatMap(instance -> requestService.findStepsByInstanceId(id).collectList()
                        .map(steps -> new WorkflowInstanceDetail(instance, steps)));
    }

    @GetMapping("/{id}/audit")
    @Operation(summary = "Get full audit trail for a request")
    public Mono<PageResponse<AuditTrail>> getAudit(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").ascending());
        return auditTrailRepository.findByInstanceIdOrderByCreatedAtAsc(id, pageable).collectList()
                .zipWith(auditTrailRepository.countByInstanceId(id))
                .map(t -> PageResponse.of(t.getT1(), page, size, t.getT2()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancel a pending request")
    public Mono<Void> cancel(@PathVariable UUID id, Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return requestService.cancel(id, userId).then();
    }

    public record WorkflowInstanceDetail(WorkflowInstance instance, List<InstanceStep> steps) {}
}
