package com.ascend.workflow.api;

import com.ascend.workflow.domain.model.GroupMember;
import com.ascend.workflow.domain.model.UserGroup;
import com.ascend.workflow.domain.service.GroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
@Tag(name = "Groups", description = "User group management")
@SecurityRequirement(name = "bearerAuth")
public class GroupController {

    private final GroupService groupService;

    public record CreateGroupRequest(@NotBlank String name, String description) {}
    public record AddMemberRequest(@NotNull UUID userId) {}

    @GetMapping
    @Operation(summary = "List all groups")
    public Flux<UserGroup> list() {
        return groupService.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a user group")
    public Mono<UserGroup> create(@Valid @RequestBody CreateGroupRequest request) {
        return groupService.createGroup(request.name(), request.description());
    }

    @PostMapping("/{groupId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add a user to a group")
    public Mono<GroupMember> addMember(@PathVariable UUID groupId,
                                       @Valid @RequestBody AddMemberRequest request) {
        return groupService.addMember(groupId, request.userId());
    }
}
