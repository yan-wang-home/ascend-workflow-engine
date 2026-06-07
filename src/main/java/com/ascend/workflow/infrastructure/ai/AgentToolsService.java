package com.ascend.workflow.infrastructure.ai;

import com.ascend.workflow.api.dto.*;
import com.ascend.workflow.domain.model.DecisionAction;
import com.ascend.workflow.domain.model.WorkflowInstance;
import com.ascend.workflow.domain.model.WorkflowTemplate;
import com.ascend.workflow.domain.service.*;
import com.ascend.workflow.infrastructure.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentToolsService {

    private final WorkflowService workflowService;
    private final RequestService requestService;
    private final ApprovalService approvalService;
    private final DelegationService delegationService;
    private final GroupService groupService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public String dispatch(String toolName, JsonNode input, UUID userId) {
        try {
            return switch (toolName) {
                case "list_workflow_templates"  -> listWorkflowTemplates();
                case "get_pending_approvals"    -> getPendingApprovals(userId);
                case "get_request_details"      -> getRequestDetails(input, userId);
                case "get_audit_history"        -> getAuditHistory(input);
                case "submit_request"           -> submitRequest(input, userId);
                case "make_decision"            -> makeDecision(input, userId);
                case "create_workflow_template" -> createWorkflowTemplate(input, userId);
                case "create_delegation"        -> createDelegation(input, userId);
                case "list_users"               -> listUsers();
                case "list_groups"              -> listGroups();
                case "create_group"             -> createGroup(input);
                case "add_group_member"         -> addGroupMember(input);
                case "list_delegations"         -> listDelegations(userId);
                case "revoke_delegation"        -> revokeDelegation(input, userId);
                default                         -> error("Unknown tool: " + toolName);
            };
        } catch (Exception e) {
            log.error("Tool execution error: tool={} userId={}", toolName, userId, e);
            return error("Tool '" + toolName + "' failed: " + e.getMessage());
        }
    }

    private String listWorkflowTemplates() throws Exception {
        List<WorkflowTemplate> templates = workflowService
                .findAll(PageRequest.of(0, 50, Sort.by("name").ascending()))
                .collectList().block();
        return ok(Map.of("templates", templates, "count", templates.size()));
    }

    private String getPendingApprovals(UUID userId) throws Exception {
        List<WorkflowInstance> items = approvalService
                .getInbox(userId, PageRequest.of(0, 50))
                .collectList().block();
        return ok(Map.of("pendingApprovals", items, "count", items.size()));
    }

    private String getRequestDetails(JsonNode input, UUID userId) throws Exception {
        UUID requestId = UUID.fromString(input.get("requestId").asText());
        WorkflowInstance instance = requestService.findById(requestId).block();
        List<?> steps = requestService.findStepsByInstanceId(requestId).collectList().block();
        return ok(Map.of("request", instance, "steps", steps));
    }

    private String getAuditHistory(JsonNode input) throws Exception {
        UUID requestId = UUID.fromString(input.get("requestId").asText());
        List<?> steps = requestService.findStepsByInstanceId(requestId).collectList().block();
        return ok(Map.of("auditEntries", steps));
    }

    private String submitRequest(JsonNode input, UUID requesterId) throws Exception {
        UUID templateId = UUID.fromString(input.get("templateId").asText());
        String title = input.get("title").asText();
        Map<String, Object> metadata = objectMapper.convertValue(input.get("metadata"), Map.class);
        SubmitRequestDto dto = new SubmitRequestDto(templateId, title, metadata);
        WorkflowInstance instance = requestService.submit(dto, requesterId).block();
        return ok(Map.of("requestId", instance.getId(), "status", instance.getStatus(),
                "message", "Request submitted successfully. ID: " + instance.getId()));
    }

    private String makeDecision(JsonNode input, UUID approverId) throws Exception {
        UUID requestId = UUID.fromString(input.get("requestId").asText());
        DecisionAction action = DecisionAction.valueOf(input.get("action").asText().toUpperCase());
        String comment = input.has("comment") ? input.get("comment").asText() : null;
        DecisionRequest dto = new DecisionRequest(action, comment);
        var decision = approvalService.decide(requestId, approverId, dto).block();
        return ok(Map.of("decisionId", decision.getId(), "action", action.name(),
                "message", "Decision recorded: " + action.name() + " on request " + requestId));
    }

    private String createWorkflowTemplate(JsonNode input, UUID createdBy) throws Exception {
        CreateWorkflowRequest dto = objectMapper.treeToValue(input, CreateWorkflowRequest.class);
        WorkflowTemplate template = workflowService.create(dto, createdBy).block();
        return ok(Map.of("templateId", template.getId(), "name", template.getName(),
                "message", "Workflow template '" + template.getName() + "' created successfully"));
    }

    private String createDelegation(JsonNode input, UUID delegatorId) throws Exception {
        UUID delegateId = UUID.fromString(input.get("delegateId").asText());
        OffsetDateTime startsAt = OffsetDateTime.parse(input.get("startsAt").asText());
        OffsetDateTime endsAt = OffsetDateTime.parse(input.get("endsAt").asText());
        UUID templateId = input.has("templateId") && !input.get("templateId").isNull()
                ? UUID.fromString(input.get("templateId").asText()) : null;
        CreateDelegationRequest dto = new CreateDelegationRequest(delegateId, templateId, startsAt, endsAt);
        var delegation = delegationService.create(dto, delegatorId).block();
        return ok(Map.of("delegationId", delegation.getId(),
                "message", "Delegation created successfully until " + endsAt));
    }

    private String listGroups() throws Exception {
        var groups = groupService.findAll().collectList().block();
        return ok(Map.of("groups", groups, "count", groups.size()));
    }

    private String createGroup(JsonNode input) throws Exception {
        String name = input.get("name").asText();
        String description = input.has("description") ? input.get("description").asText() : "";
        var group = groupService.createGroup(name, description).block();
        return ok(Map.of("groupId", group.getId(), "name", group.getName(),
                "message", "Group '" + group.getName() + "' created successfully"));
    }

    private String addGroupMember(JsonNode input) throws Exception {
        UUID groupId = UUID.fromString(input.get("groupId").asText());
        UUID userId = UUID.fromString(input.get("userId").asText());
        var member = groupService.addMember(groupId, userId).block();
        return ok(Map.of("groupId", member.getGroupId(), "userId", member.getUserId(),
                "message", "User added to group successfully"));
    }

    private String listDelegations(UUID delegatorId) throws Exception {
        var delegations = delegationService.findByDelegator(delegatorId, PageRequest.of(0, 50)).collectList().block();
        return ok(Map.of("delegations", delegations, "count", delegations.size()));
    }

    private String revokeDelegation(JsonNode input, UUID delegatorId) throws Exception {
        UUID delegationId = UUID.fromString(input.get("delegationId").asText());
        delegationService.revoke(delegationId, delegatorId).block();
        return ok(Map.of("message", "Delegation " + delegationId + " revoked successfully"));
    }

    private String listUsers() throws Exception {
        var users = userRepository.findAll()
                .filter(u -> !u.getId().toString().equals("00000000-0000-0000-0000-000000000001"))
                .map(u -> Map.of("id", u.getId(), "name", u.getName(), "email", u.getEmail(), "role", u.getRole()))
                .collectList().block();
        return ok(Map.of("users", users, "count", users.size()));
    }

    private String ok(Map<String, Object> data) throws Exception {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("success", true);
        result.putAll(data);
        return objectMapper.writeValueAsString(result);
    }

    private String error(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("success", false, "errorMessage", message));
        } catch (Exception e) {
            return "{\"success\":false,\"errorMessage\":\"" + message + "\"}";
        }
    }
}
