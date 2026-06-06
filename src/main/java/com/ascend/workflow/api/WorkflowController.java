package com.ascend.workflow.api;

import com.ascend.workflow.api.dto.CreateWorkflowRequest;
import com.ascend.workflow.api.dto.PageResponse;
import com.ascend.workflow.domain.model.WorkflowTemplate;
import com.ascend.workflow.domain.service.WorkflowService;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
@Tag(name = "Workflows", description = "Workflow template administration")
@SecurityRequirement(name = "bearerAuth")
public class WorkflowController {

    private final WorkflowService workflowService;

    @GetMapping
    @Operation(summary = "List all workflow templates (paginated)")
    public Mono<PageResponse<WorkflowTemplate>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return workflowService.findAll(pageable).collectList()
                .zipWith(workflowService.count())
                .map(t -> PageResponse.of(t.getT1(), page, size, t.getT2()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a workflow template")
    public Mono<WorkflowTemplate> create(@Valid @RequestBody CreateWorkflowRequest request,
                                          Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return workflowService.create(request, userId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a workflow template by ID")
    public Mono<WorkflowTemplate> getById(@PathVariable UUID id) {
        return workflowService.findById(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a workflow template")
    public Mono<WorkflowTemplate> update(@PathVariable UUID id,
                                          @Valid @RequestBody CreateWorkflowRequest request,
                                          Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return workflowService.update(id, request, userId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate a workflow template")
    public Mono<Void> delete(@PathVariable UUID id) {
        return workflowService.delete(id);
    }
}
