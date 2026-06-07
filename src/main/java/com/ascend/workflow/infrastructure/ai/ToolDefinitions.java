package com.ascend.workflow.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Defines all Anthropic tool schemas.
 * These are sent to the API so Claude knows what tools are available and what inputs they expect.
 */
@Component
public class ToolDefinitions {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<ObjectNode> all() {
        return List.of(
                tool("list_workflow_templates",
                        "List all available workflow templates the user can choose from when submitting a request",
                        schema()),

                tool("get_pending_approvals",
                        "Retrieve all approval requests currently waiting for the current user's decision, including delegated approvals",
                        schema()),

                tool("get_request_details",
                        "Get full details of a specific approval request including its current step and history",
                        schema("requestId", "string", "The UUID of the approval request")),

                tool("get_audit_history",
                        "Retrieve the full audit trail for a specific approval request",
                        schema("requestId", "string", "The UUID of the approval request")),

                tool("submit_request",
                        "Submit a new approval request. Only call after collecting all required information and receiving explicit user confirmation. " +
                        "Required: templateId (UUID), title (string), metadata (object with request details like amount, vendor, description)",
                        schemaForSubmit()),

                tool("make_decision",
                        "Approve, reject, or request changes on an approval request. Only call after explicit user confirmation. " +
                        "action must be APPROVE, REJECT, or REQUEST_CHANGES",
                        schemaForDecision()),

                tool("create_workflow_template",
                        "Create a new workflow template. Only call after presenting the configuration to the user and receiving confirmation",
                        schemaForCreateTemplate()),

                tool("create_delegation",
                        "Temporarily delegate approval authority to another user. Only call after explicit user confirmation",
                        schemaForDelegation()),

                tool("list_users",
                        "List all registered users with their id, name, email, and role. Use this to look up approver UUIDs when creating workflow templates",
                        schema()),

                tool("list_groups",
                        "List all approval groups with their id, name, and description. Use this to look up group UUIDs when creating group-based workflow templates",
                        schema()),

                tool("create_group",
                        "Create a new approval group. Only call after explicit user confirmation",
                        schema("name", "string", "Group name", "description", "string", "What this group is used for")),

                tool("add_group_member",
                        "Add a user to an approval group. Only call after explicit user confirmation",
                        schema("groupId", "string", "UUID of the group", "userId", "string", "UUID of the user to add")),

                tool("list_delegations",
                        "List all delegations created by the current user",
                        schema()),

                tool("revoke_delegation",
                        "Revoke (permanently delete) a delegation. Only call after explicit user confirmation",
                        schema("delegationId", "string", "UUID of the delegation to revoke"))
        );
    }

    private ObjectNode tool(String name, String description, ObjectNode inputSchema) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("input_schema", inputSchema);
        return tool;
    }

    private ObjectNode schema(String... fieldNameTypeDesc) {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = mapper.createObjectNode();
        var required = mapper.createArrayNode();

        for (int i = 0; i < fieldNameTypeDesc.length; i += 3) {
            String name = fieldNameTypeDesc[i];
            String type = fieldNameTypeDesc[i + 1];
            String desc = fieldNameTypeDesc[i + 2];
            properties.set(name, mapper.createObjectNode().put("type", type).put("description", desc));
            required.add(name);
        }

        schema.set("properties", properties);
        if (required.size() > 0) schema.set("required", required);
        return schema;
    }

    private ObjectNode schema() {
        return mapper.createObjectNode().put("type", "object")
                .set("properties", mapper.createObjectNode());
    }

    private ObjectNode schemaForSubmit() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.set("templateId", mapper.createObjectNode().put("type", "string").put("description", "UUID of the workflow template to use"));
        props.set("title", mapper.createObjectNode().put("type", "string").put("description", "Short title for the request"));
        props.set("metadata", mapper.createObjectNode().put("type", "object").put("description", "Request details as key-value pairs (amount, vendor, description, etc.)"));
        schema.set("properties", props);
        schema.set("required", mapper.createArrayNode().add("templateId").add("title").add("metadata"));
        return schema;
    }

    private ObjectNode schemaForDecision() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.set("requestId", mapper.createObjectNode().put("type", "string").put("description", "UUID of the request to decide on"));
        props.set("action", mapper.createObjectNode().put("type", "string").put("description", "APPROVE, REJECT, or REQUEST_CHANGES"));
        props.set("comment", mapper.createObjectNode().put("type", "string").put("description", "Optional comment explaining the decision"));
        schema.set("properties", props);
        schema.set("required", mapper.createArrayNode().add("requestId").add("action"));
        return schema;
    }

    private ObjectNode schemaForCreateTemplate() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.set("name", mapper.createObjectNode().put("type", "string").put("description", "Template name"));
        props.set("description", mapper.createObjectNode().put("type", "string").put("description", "What this workflow is used for"));
        props.set("steps", mapper.createObjectNode().put("type", "array").put("description",
                "List of approval steps. Each step: stepOrder (int), name (string), approverType (USER|GROUP|ROLE), " +
                "approverId (UUID of user or group), approvalMode (ANY_OF|ALL_OF, for GROUP only), " +
                "parallelGroup (string tag — steps with same stepOrder and parallelGroup run in parallel), " +
                "timeoutHours (int, optional — escalate after this many hours), " +
                "escalationUserId (UUID, optional — who to escalate to), " +
                "conditions (array of {field, operator, value} — skip step if conditions not met)"));
        schema.set("properties", props);
        schema.set("required", mapper.createArrayNode().add("name").add("steps"));
        return schema;
    }

    private ObjectNode schemaForDelegation() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.set("delegateId", mapper.createObjectNode().put("type", "string").put("description", "UUID of the user to delegate to"));
        props.set("startsAt", mapper.createObjectNode().put("type", "string").put("description", "ISO-8601 start datetime"));
        props.set("endsAt", mapper.createObjectNode().put("type", "string").put("description", "ISO-8601 end datetime"));
        props.set("templateId", mapper.createObjectNode().put("type", "string").put("description", "Optional UUID of specific template to scope delegation to; omit for all templates"));
        schema.set("properties", props);
        schema.set("required", mapper.createArrayNode().add("delegateId").add("startsAt").add("endsAt"));
        return schema;
    }
}
