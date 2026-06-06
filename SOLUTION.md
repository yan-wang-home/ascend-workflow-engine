# Solution Overview

## Part 1 — Data Model

The schema (`V1__init.sql`) has 13 tables organised around three concerns:

### Core workflow tables
| Table | Purpose |
|---|---|
| `users` | Authenticated users with roles: `ADMIN`, `APPROVER`, `REQUESTER` |
| `user_groups` / `group_members` | Group-based approver pools |
| `workflow_templates` | Blueprint for an approval process |
| `workflow_steps` | Ordered steps within a template |
| `step_conditions` | Per-step conditional rules evaluated against request metadata |
| `workflow_instances` | A live approval request (instantiated from a template) |
| `instance_steps` | The active copy of each step for a specific request |
| `decisions` | Individual approve/reject/request-changes actions |
| `delegations` | Temporary authority grants between users |
| `audit_trail` | Immutable event log for every state change |

### Agent tables
| Table | Purpose |
|---|---|
| `agent_sessions` | Per-user conversation history (JSONB) |
| `agent_logs` | Observability: every chat turn with tool calls and duration |

### Key design decisions

**`parallel_group` on `workflow_steps`** — steps sharing the same non-null integer are executed simultaneously. The workflow advances only when all steps in the group reach `APPROVED` or `SKIPPED`.

**`metadata JSONB` on `workflow_instances`** — allows any request type (purchase orders, hiring approvals, expense claims) without schema changes. `step_conditions` evaluate against this field at runtime.

**`escalated_at` on `instance_steps`** — a nullable timestamp set when a step is escalated. `EscalationService` re-fetches the step at fire time and checks `escalated_at IS NULL` before acting, preventing double-escalation if a concurrent decision arrives just as the timer fires.

## Part 2 — REST API

Six controllers expose 24+ endpoints, all documented in Swagger UI.

### Endpoints by domain

**Auth** — `POST /api/v1/auth/register`, `POST /api/v1/auth/login`

**Workflows (templates)** — CRUD for workflow templates and their steps. Only `ADMIN` users can create or modify templates.

**Requests** — `POST /api/v1/requests` submits a new approval request (instantiates a template). `GET /api/v1/requests` lists the caller's own requests with status filtering.

**Approvals** — `GET /api/v1/approvals/inbox` returns all pending items for the current user, merging direct approvals, group-based approvals, and delegated authority into one deduplicated list. `POST /api/v1/approvals/{requestId}/decide` records a decision and advances the workflow.

**Delegations** — create and list approval authority delegations, optionally scoped to a single template.

**Agent** — `POST /api/v1/agent/chat` (multi-turn AI assistant), `GET /api/v1/agent/logs` (admin only).

### Advanced routing features

- **Conditional steps** — `step_conditions` with operators `EQ`, `NEQ`, `GT`, `GTE`, `LT`, `LTE`, `IN`, `CONTAINS` evaluated against request metadata. Steps whose conditions do not match are auto-skipped.
- **Parallel approvals** — steps with the same `parallel_group` are activated simultaneously. The workflow waits for all to complete before advancing.
- **Group approvals** — steps can target a `user_group` instead of an individual user; any group member can decide.
- **Escalation** — `EscalationService` uses `ThreadPoolTaskScheduler` to schedule an exact-time callback when a step becomes `PENDING`. The trigger fires at `startedAt + (timeoutHours × timeout-hour-in-seconds)`. On fire it re-fetches the step, checks `escalated_at IS NULL` (idempotency guard), marks it `ESCALATED`, and creates a new `PENDING` step for the designated escalation user. Decisions cancel the pending timer. Set `app.escalation.timeout-hour-in-seconds=60` to demo escalation in real time.
- **Delegations** — `getInbox` merges delegated requests so a delegate sees items they are covering on behalf of the delegator.

### Error handling

`GlobalExceptionHandler` maps domain exceptions to consistent HTTP responses:

| Exception | HTTP Status |
|---|---|
| `ResourceNotFoundException` | 404 |
| `SecurityException` | 403 |
| `IllegalStateException` | 409 |
| `IllegalArgumentException` | 400 |
| `WebExchangeBindException` | 400 (validation) |
| Unhandled | 500 |

## Part 3 — Agentic Workflow Assistant

### Architecture

The agent is a sibling to the REST API — both call the same service layer directly. There are no internal HTTP calls.

```
POST /api/v1/agent/chat
        │
        ▼
  AgentService          ← manages session, runs the tool loop
        │
        ├── AnthropicClient    ← WebClient calling api.anthropic.com/v1/messages
        ├── ToolDefinitions    ← 8 tool schemas sent to Claude each turn
        └── AgentToolsService  ← dispatches tool calls to service layer
```

### Tool-calling loop

1. Load conversation history from `agent_sessions` (JSONB).
2. Append the new user message and call Claude with all tool schemas.
3. If `stop_reason == "tool_use"`, execute all tool calls via `AgentToolsService.dispatch()` and send results back as a user turn.
4. Repeat up to 10 iterations.
5. Return the final text response, persist the turn to `agent_sessions`, and log to `agent_logs`.

### Available tools

| Tool | Description |
|---|---|
| `list_workflow_templates` | Lists all available templates |
| `get_pending_approvals` | Returns the caller's inbox |
| `get_request_details` | Full detail of a specific request |
| `get_audit_history` | Audit trail for a request |
| `submit_request` | Creates a new approval request |
| `make_decision` | Approves, rejects, or requests changes |
| `create_workflow_template` | Creates a new workflow template |
| `create_delegation` | Sets up a temporary delegation |

### Confirmation before write actions

The system prompt instructs Claude to summarise intended write actions (`submit_request`, `make_decision`, `create_delegation`, `create_workflow_template`) and ask "Shall I proceed?" before calling the tool. The tool is only executed on the next user turn once confirmation is given.

### Observability

Every chat turn is written to `agent_logs` with: user message, all tool calls (name + input + result), final assistant response, and wall-clock duration. Accessible via `GET /api/v1/agent/logs` (admin only).

## Technology Choices

| Concern | Choice | Reason |
|---|---|---|
| Runtime | Java 21 + Spring WebFlux | Non-blocking I/O suits the async nature of LLM calls and DB queries |
| Database access | Spring Data R2DBC | Reactive, no JPA overhead; PostgreSQL JSONB for flexible metadata |
| Migrations | Flyway | Simple, ordered SQL migrations; runs on startup |
| Security | Spring Security + JWT | Stateless, works naturally with WebFlux's reactive security model |
| AI integration | Direct Anthropic REST API | Full control over the tool-calling loop; no Spring AI dependency required |
| API docs | Springdoc OpenAPI | Auto-generated from annotations; Swagger UI available out of the box |
| Build | Gradle | Flexible dependency management |
