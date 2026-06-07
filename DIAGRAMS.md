# Architecture Diagrams

## 1. Workflow Execution Flow

```mermaid
flowchart TD
    A([Submit Request]) --> B
    B["Evaluate step_conditions against metadata\nMatching steps → PENDING · Non-matching → SKIPPED"]
    B --> C["PENDING steps enter Approver Inbox\ndirect approver · group member · active delegate"]
    C --> D{Decision}

    D -->|REJECT| E([REJECTED ✗])

    D -->|REQUEST_CHANGES| F["Requester updates and resubmits"]
    F --> C

    D -->|APPROVE| G{"ALL_OF group?\nAll members approved?"}
    G -->|"Not yet"| C
    G -->|"Yes, or not ALL_OF"| H{"Parallel group?\nAll steps done?"}
    H -->|"Not yet"| C
    H -->|"Yes, or no parallel group"| I{More step_orders?}
    I -->|"Yes — advance"| B
    I -->|No| J([APPROVED ✓])

    ESC["EscalationService\nStep times out → ESCALATED\nNew PENDING step for escalation user"]
    C -.->|on timeout| ESC
    ESC -.->|re-enters inbox| C
```

---

## 2. System Architecture

```mermaid
graph LR
    Client["Client\nPostman / Swagger UI"]

    Client -->|"HTTP + Bearer JWT"| JWT["JWT Filter\nextracts userId + role"]

    subgraph App["Spring Boot — port 8080"]
        JWT

        subgraph REST["REST API Layer"]
            direction TB
            AuthCtrl["AuthController\nPOST /auth/register\nPOST /auth/login"]
            WorkflowCtrl["WorkflowController\nCRUD /workflows"]
            RequestCtrl["RequestController\nPOST /requests · GET /requests"]
            ApprovalCtrl["ApprovalController\nGET /approvals/inbox\nPOST /approvals/:id/decide"]
            DelegationCtrl["DelegationController\n/delegations"]
            GroupCtrl["GroupController\n/groups"]
        end

        subgraph AgentBox["Agent Layer"]
            direction TB
            AgentCtrl["AgentController\nPOST /agent/chat\nGET  /agent/logs"]
            AgentSvc["AgentService\ntool-calling loop · up to 10 iterations"]
            AgentTools["AgentToolsService\ndispatches tool calls to services"]
            ToolDefs["ToolDefinitions\n14 tool schemas"]
            AnthClient["AnthropicClient\nWebClient"]
            AgentCtrl ~~~ AgentSvc
            AgentSvc ~~~ AgentTools
            AgentTools ~~~ ToolDefs
            ToolDefs ~~~ AnthClient
        end

        subgraph Services["Service Layer — shared by REST and Agent"]
            direction TB
            AuthSvc["AuthService"]
            WorkflowSvc["WorkflowService"]
            RequestSvc["RequestService\n+ ConditionEvaluator"]
            ApprovalSvc["ApprovalService"]
            DelegationSvc["DelegationService"]
            GroupSvc["GroupService"]
            EscSvc["EscalationService\nThreadPoolTaskScheduler"]
            AuditSvc["AuditService\n(immutable audit trail)"]
        end

        Repos["Repository Layer\n12 Spring Data R2DBC repositories"]
    end

    DB[("PostgreSQL\nascend_workflow\n13 tables")]
    AnthAPI["Anthropic API\napi.anthropic.com\nClaude claude-sonnet-4-6"]

    JWT --> AuthCtrl
    JWT --> WorkflowCtrl
    JWT --> RequestCtrl
    JWT --> ApprovalCtrl
    JWT --> DelegationCtrl
    JWT --> GroupCtrl
    JWT --> AgentCtrl
    AgentCtrl --> AgentSvc
    AgentSvc --> AgentTools
    AgentSvc --> ToolDefs
    AgentSvc --> AnthClient

    AuthCtrl      --> AuthSvc
    WorkflowCtrl  --> WorkflowSvc
    RequestCtrl   --> RequestSvc
    ApprovalCtrl  --> ApprovalSvc
    DelegationCtrl --> DelegationSvc
    GroupCtrl     --> GroupSvc

    AgentTools --> WorkflowSvc
    AgentTools --> RequestSvc
    AgentTools --> ApprovalSvc
    AgentTools --> DelegationSvc
    AgentTools --> GroupSvc

    AnthClient -->|HTTPS| AnthAPI

    AuthSvc    --> AuditSvc & Repos
    WorkflowSvc --> AuditSvc & Repos
    RequestSvc  --> AuditSvc & Repos & EscSvc
    ApprovalSvc --> AuditSvc & Repos & EscSvc
    DelegationSvc --> AuditSvc & Repos
    GroupSvc   --> Repos
    EscSvc     --> AuditSvc & Repos
    AuditSvc   --> Repos

    Repos -->|"R2DBC reactive"| DB
```

---

## 3. Agent Layer — Detailed

```mermaid
flowchart TD
    A["POST /api/v1/agent/chat\n{ sessionId?, message }"]

    A --> B["AgentController\nExtract userId from JWT\nResolve or create AgentSession"]

    B --> C

    subgraph Loop["AgentService — Tool-Calling Loop (up to 10 iterations)"]
        direction TB
        C["Load conversation_history\nfrom agent_sessions (JSONB)\nAppend new user message"]
        C --> D["Build Anthropic request:\nfull message history + all 14 tool schemas"]
        D --> E["AnthropicClient\nPOST api.anthropic.com/v1/messages\n(WebClient, non-blocking)"]
        E --> F{{"stop_reason?"}}
        F -->|"tool_use"| G["Parse tool calls\nfrom response content blocks"]
        G --> H["AgentToolsService.dispatch()\nswitch on tool name → service call → JSON result"]
        H --> I["Append tool results\nas next user turn"]
        I --> D
        F -->|"end_turn"| J["Final text response ready"]
    end

    J --> K["Persist turn\nagent_sessions.conversation_history\n(text pairs only — stays compact\nacross many turns)"]
    J --> L["agent_logs\nuser_message + all tool_calls JSONB\n(name, input, result, duration_ms)\n+ final assistant_response"]
    J --> M["Return text to caller"]

    subgraph Tools["AgentToolsService — 14 Tools"]
        direction LR
        subgraph Read["Read-only (no confirmation)"]
            R1["list_workflow_templates"]
            R2["get_pending_approvals"]
            R3["get_request_details"]
            R4["get_audit_history"]
            R5["list_users"]
            R6["list_groups"]
            R7["list_delegations"]
        end
        subgraph Write["Write (plan → confirm → execute)"]
            W1["create_workflow_template"]
            W2["submit_request"]
            W3["make_decision"]
            W4["create_delegation"]
            W5["revoke_delegation"]
            W6["create_group"]
            W7["add_group_member"]
        end
    end

    H -->|"direct service call\nno internal HTTP"| SvcLayer["WorkflowService · RequestService\nApprovalService · DelegationService\nGroupService · UserRepository"]

    Tools -.->|"used by"| H
```

**Key design points for the walkthrough:**
- The loop runs entirely server-side — the client sends one HTTP request and gets one response back
- Read tools execute immediately; write tools always present a plan and wait for the user to say "yes" before calling the tool
- `conversation_history` stores only text pairs (compact); full tool traces go to `agent_logs` for observability
- `AgentToolsService` calls service classes directly — no internal HTTP, no re-auth

---

## 4. Data Model

```mermaid
erDiagram
    users {
        uuid id PK
        varchar email
        varchar password_hash
        varchar name
        varchar role "ADMIN | APPROVER | REQUESTER"
    }

    user_groups {
        uuid id PK
        varchar name
        text description
    }

    group_members {
        uuid group_id FK
        uuid user_id FK
    }

    workflow_templates {
        uuid id PK
        varchar name
        text description
        boolean is_active
        uuid created_by FK
    }

    workflow_steps {
        uuid id PK
        uuid template_id FK
        int step_order
        int parallel_group "null = sequential"
        varchar name
        varchar approver_type "USER | GROUP | ROLE"
        varchar approver_id
        varchar approval_mode "ANY_OF | ALL_OF"
        int timeout_hours
        uuid escalation_user_id FK
    }

    step_conditions {
        uuid id PK
        uuid step_id FK
        varchar field_name "matches metadata key"
        varchar operator "EQ|NEQ|GT|GTE|LT|LTE|IN|CONTAINS"
        varchar value
    }

    workflow_instances {
        uuid id PK
        uuid template_id FK
        uuid requester_id FK
        varchar title
        varchar status "PENDING|APPROVED|REJECTED|CANCELLED"
        jsonb metadata "freeform request data"
        int current_step_order
    }

    instance_steps {
        uuid id PK
        uuid instance_id FK
        uuid step_id FK
        int step_order
        int parallel_group
        varchar status "PENDING|APPROVED|REJECTED|SKIPPED|ESCALATED"
        timestamptz started_at
        timestamptz completed_at
        timestamptz escalated_at "set to prevent double-escalation"
        uuid escalated_to_user_id FK
    }

    decisions {
        uuid id PK
        uuid instance_step_id FK
        uuid approver_id FK
        varchar action "APPROVE|REJECT|REQUEST_CHANGES"
        text comment
        timestamptz decided_at
    }

    delegations {
        uuid id PK
        uuid delegator_id FK
        uuid delegate_id FK
        uuid template_id FK "null = all templates"
        timestamptz starts_at
        timestamptz ends_at
        boolean is_active
    }

    audit_trail {
        uuid id PK
        uuid instance_id FK
        uuid user_id FK
        varchar action
        jsonb details
        timestamptz created_at
    }

    agent_sessions {
        uuid id PK
        uuid user_id FK
        jsonb conversation_history "array of role/content pairs"
        timestamptz updated_at
    }

    agent_logs {
        uuid id PK
        uuid session_id FK
        text user_message
        jsonb tool_calls "name + input + result per tool"
        text assistant_response
        bigint duration_ms
    }

    users            ||--o{ group_members      : "belongs to"
    user_groups      ||--o{ group_members      : "has"
    users            ||--o{ workflow_templates  : "creates"
    workflow_templates ||--o{ workflow_steps    : "defines"
    workflow_steps   ||--o{ step_conditions    : "has"
    users            ||--o{ workflow_instances  : "submits"
    workflow_templates ||--o{ workflow_instances : "instantiated as"
    workflow_instances ||--o{ instance_steps   : "has"
    workflow_steps   ||--o{ instance_steps     : "copied into"
    instance_steps   ||--o{ decisions          : "receives"
    users            ||--o{ decisions          : "makes"
    users            ||--o{ delegations        : "delegates from"
    users            ||--o{ delegations        : "delegates to"
    workflow_templates ||--o{ delegations      : "scoped to"
    workflow_instances ||--o{ audit_trail      : "logged for"
    users            ||--o{ agent_sessions     : "has"
    agent_sessions   ||--o{ agent_logs         : "contains"
```
