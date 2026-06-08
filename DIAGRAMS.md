# Architecture Diagrams

## 0. Overall User Flow

```mermaid
flowchart TD
    subgraph Setup["Setup — Admin configures once"]
        A["Admin\nCreate Workflow Template"]
    end

    subgraph Execution["Execution — repeats per request"]
        B["Requester\nSubmit Request"]
        C["Workflow Engine\nEvaluate step_conditions"]
        D["Approver Inbox"]
        E{Approver Decision}
    end

    A --> B
    B --> C
    C --> D
    D --> E

    E -->|"APPROVE — more steps"| C
    E -->|REQUEST_CHANGES| B
    E -->|REJECT| REJ([REJECTED ✗])
    E -->|"APPROVE — done"| APP([APPROVED ✓])

    AGT["AI Agent"]
    AGT -.->|"acts as any role"| Execution
```

---

## 1. System Architecture

```mermaid
graph LR
    Client["Client"]

    Client -->|"HTTP + Bearer JWT"| JWT["JWT Filter"]

    subgraph App["Spring Boot — port 8080"]
        JWT

        subgraph REST["REST API Layer"]
            direction TB
            AuthCtrl["AuthController"]
            WorkflowCtrl["WorkflowController"]
            RequestCtrl["RequestController"]
            ApprovalCtrl["ApprovalController"]
            DelegationCtrl["DelegationController"]
            GroupCtrl["GroupController"]
        end

        subgraph AgentBox["Agent Layer"]
            direction TB
            AgentCtrl["AgentController"]
            AgentSvc["AgentService"]
            AgentTools["AgentToolsService"]
            ToolDefs["ToolDefinitions"]
            AnthClient["AnthropicClient"]
            AgentCtrl ~~~ AgentSvc
            AgentSvc ~~~ AgentTools
            AgentTools ~~~ ToolDefs
            ToolDefs ~~~ AnthClient
        end

        subgraph Services["Service Layer"]
            direction TB
            AuthSvc["AuthService"]
            WorkflowSvc["WorkflowService"]
            RequestSvc["RequestService"]
            ApprovalSvc["ApprovalService"]
            DelegationSvc["DelegationService"]
            GroupSvc["GroupService"]
            EscSvc["EscalationService"]
            AuditSvc["AuditService"]
        end

        Repos["Repository Layer"]
    end

    DB[("PostgreSQL")]
    AnthAPI["Anthropic API"]

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

## 2. Agent Layer — Detailed

```mermaid
flowchart TD
    A["POST /agent/chat"]

    A --> B["AgentController"]

    B --> C

    subgraph Loop["AgentService — Tool-Calling Loop (up to 10 iterations)"]
        direction TB
        C["Load conversation history\nAppend user message"]
        C --> D["Build request\nwith 14 tool schemas"]
        D --> E["AnthropicClient\nPOST to Anthropic"]
        E --> F{{"stop_reason?"}}
        F -->|"tool_use"| G["Parse tool calls"]
        G --> H["AgentToolsService\ndispatch tool calls"]
        H --> I["Append tool results"]
        I --> D
        F -->|"end_turn"| J["Text response ready"]
    end

    J --> K["Persist to\nconversation_history"]
    J --> L["Log to agent_logs\n(full tool trace)"]
    J --> M["Return response"]

    subgraph Tools["AgentToolsService — 14 Tools"]
        direction LR
        subgraph Read["Read-only"]
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

    H --> SvcLayer["Service Layer"]

    Tools -.->|"used by"| H
```

**Key design points for the walkthrough:**
- The loop runs entirely server-side — the client sends one HTTP request and gets one response back
- Read tools execute immediately; write tools always present a plan and wait for the user to say "yes" before calling the tool
- `conversation_history` stores only text pairs (compact); full tool traces go to `agent_logs` for observability
- `AgentToolsService` calls service classes directly — no internal HTTP, no re-auth

---

## 3. Data Model

```mermaid
erDiagram
    %% ── Identity ──────────────────────────────────────────
    users {
        uuid id PK
        varchar email
        varchar password_hash
        varchar name
        varchar role
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

    %% ── Template — design-time ────────────────────────────
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
        int parallel_group
        varchar name
        varchar approver_type
        varchar approver_id
        varchar approval_mode
        int timeout_hours
        uuid escalation_user_id FK
    }

    step_conditions {
        uuid id PK
        uuid step_id FK
        varchar field_name
        varchar operator
        varchar value
    }

    %% ── Execution — runtime ───────────────────────────────
    workflow_instances {
        uuid id PK
        uuid template_id FK
        uuid requester_id FK
        varchar title
        varchar status
        jsonb metadata
        int current_step_order
    }

    instance_steps {
        uuid id PK
        uuid instance_id FK
        uuid step_id FK
        int step_order
        int parallel_group
        varchar status
        timestamptz started_at
        timestamptz completed_at
        timestamptz escalated_at
        uuid escalated_to_user_id FK
    }

    decisions {
        uuid id PK
        uuid instance_step_id FK
        uuid approver_id FK
        varchar action
        text comment
        timestamptz decided_at
    }

    %% ── Delegation ────────────────────────────────────────
    delegations {
        uuid id PK
        uuid delegator_id FK
        uuid delegate_id FK
        uuid template_id FK
        timestamptz starts_at
        timestamptz ends_at
        boolean is_active
    }

    %% ── System ────────────────────────────────────────────
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
        jsonb conversation_history
        timestamptz updated_at
    }

    agent_logs {
        uuid id PK
        uuid session_id FK
        text user_message
        jsonb tool_calls
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

---

## Supported Workflow Execution Use Cases

### 4a. Normal Flow

```mermaid
flowchart TD
    A([Submit Request]) --> B
    B["Evaluate step_conditions\nPENDING or SKIPPED"]
    B --> C["Approver Inbox"]
    C --> D{Decision}
    D -->|REJECT| E([REJECTED ✗])
    D -->|REQUEST_CHANGES| F["Requester resubmits"]
    F --> C
    D -->|APPROVE| G{More steps?}
    G -->|Yes| B
    G -->|No| H([APPROVED ✓])
```

---

### 4b. Delegation

```mermaid
flowchart TD
    A["Delegator creates delegation"]
    A --> B["Step becomes PENDING\nassigned to Delegator"]
    B --> C["Delegate's inbox\nincludes delegated steps"]
    C --> D{Delegate decides}
    D --> E["Decision recorded\nunder Delegate's userId"]
    E --> F([APPROVED ✓])
```

---

### 4c. Group Approval — ANY_OF and ALL_OF

```mermaid
flowchart LR
    subgraph ANYOF["ANY_OF — first member is enough"]
        A1["Step assigned to group"] --> B1{First member approves}
        B1 --> C1([APPROVED ✓])
    end

    subgraph ALLOF["ALL_OF — every member must approve"]
        A2["Step assigned to group"] --> B2{Member approves}
        B2 --> C2{All members approved?}
        C2 -->|"No — wait"| B2
        C2 -->|Yes| D2([APPROVED ✓])
    end
```

---

### 4d. Parallel Approval

```mermaid
flowchart TD
    A["Parallel steps activate\nsame step_order + parallel_group"]
    A --> B["Each approver sees step in inbox"]
    B --> C{Approver decides}
    C -->|APPROVE| D{"All parallel steps\ndone?"}
    D -->|"No — wait"| B
    D -->|Yes| E["Advance to next step_order"]
    E --> F([Next steps activate])
```

---

### 4e. Escalation

```mermaid
flowchart TD
    A["Step becomes PENDING\nscheduleEscalation() called"] --> B{Decided before timeout?}
    B -->|Yes| C["cancelEscalation()\nNo escalation fires"]
    B -->|"No — timer fires"| D["doEscalate()\nRe-fetch + idempotency check"]
    D -->|"Guard fails"| E["No-op\nDecision already arrived"]
    D -->|"Guard passes"| F["Original step → ESCALATED"]
    F --> G["New PENDING step\nassigned to escalation_user_id"]
    G --> H["Escalation user inbox"]
    H --> I{Escalation user decides}
    I --> J([Workflow advances])
```
