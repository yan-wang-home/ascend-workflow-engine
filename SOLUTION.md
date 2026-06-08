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

**Auth** — `POST /api/v1/auth/register` (always creates `REQUESTER` role; demo users are seeded via `cleanup.sql`), `POST /api/v1/auth/login`

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
        ├── ToolDefinitions    ← 14 tool schemas sent to Claude each turn
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
| `create_workflow_template` | Creates a new workflow template (supports conditions, parallel groups, escalation) |
| `create_delegation` | Sets up a temporary delegation |
| `list_users` | Lists all users with id/name/email/role — used to resolve approver UUIDs |
| `list_groups` | Lists all approval groups — used to resolve group UUIDs |
| `create_group` | Creates a new approval group |
| `add_group_member` | Adds a user to an approval group |
| `list_delegations` | Lists the caller's active delegations |
| `revoke_delegation` | Permanently removes a delegation |

### Confirmation before write actions

The system prompt instructs Claude to summarise intended write actions (`submit_request`, `make_decision`, `create_delegation`, `create_workflow_template`, `create_group`, `add_group_member`, `revoke_delegation`) and ask "Shall I proceed?" before calling the tool. The tool is only executed on the next user turn once confirmation is given.

### Observability

Every chat turn is written to `agent_logs` with: user message, all tool calls (name + input + result), final assistant response, and wall-clock duration. Accessible via `GET /api/v1/agent/logs` (admin only).

## AI Usage During Development

This project was built collaboratively with **Claude Code** (Anthropic's CLI coding agent) across the full development lifecycle — not just for boilerplate, but for architecture, implementation, debugging, testing, and documentation.

### What Claude Code did

**Initial scaffold and architecture** — Claude Code generated the full Spring WebFlux + R2DBC project structure from a description of the requirements: entity models, repository interfaces, service layer, REST controllers, security config, and Flyway migrations. The 13-table schema and the reactive service chains were produced in the first session and required minimal correction.

**Complex business logic** — The trickier service methods were written with Claude Code: the 4-source `UNION` inbox query in `ApprovalService.getInbox()`, the `checkParallelGroupComplete()` logic that gates parallel step advancement, and the `ALL_OF` member-count check for group approvals. These involved non-trivial reactive operator chains that would have taken significantly longer to write by hand.

**Escalation redesign** — The first escalation implementation used a polling scheduler (every 15 min). Claude Code identified that this was un-demoable and had a race condition, then redesigned it to use `ThreadPoolTaskScheduler` with exact-time callbacks, a `ConcurrentHashMap<UUID, ScheduledFuture<?>>` for cancellation, and an idempotency guard (`re-fetch + status=PENDING && escalated_at IS NULL`) inside the callback. It also caught and fixed an infinite escalation loop bug where `doEscalate()` was accidentally re-scheduling the newly created PENDING step.

**Agent tool-calling loop** — The full `AgentService` loop, `ToolDefinitions` (14 tool schemas), and `AgentToolsService` dispatch switch were generated by Claude Code. The system prompt instruction for plan-review-confirm before write actions was also drafted and iterated with Claude Code.

**Test suite** — `ApprovalServiceTest` (11 tests covering inbox deduplication, all decision paths, parallel group completion, and ALL_OF group approval) and `EscalationServiceTest` (7 tests including the idempotency guard scenarios via captured `Runnable`) were written by Claude Code in this session. The `ScheduledFuture<?>` wildcard type issue with Mockito was diagnosed and fixed autonomously using `doReturn` instead of `thenReturn`.

**Documentation** — `DESIGN_DECISIONS.md`, `DIAGRAMS.md` (Mermaid diagrams), `DEMO.md`, and this `SOLUTION.md` were all written and iterated with Claude Code. The diagrams went through multiple rounds of revision — layout direction, node label verbosity, diagram splitting — driven by review feedback.

### Where human judgment led

- **Requirement scoping** — deciding which features to implement (parallel approval, group approval, delegation, escalation) vs. defer (notifications, multi-tenancy, role-based approver routing) was a human call based on reading the case study.
- **Stack choice** — Spring WebFlux + R2DBC over Spring MVC + JPA was decided upfront; Claude Code implemented it but did not choose it.
- **Demo narrative** — the sequencing of Postman acts, which DB tables to highlight at each step, and the agent chat script were shaped through back-and-forth conversation rather than a single prompt.
- **Design review** — Claude Code's first escalation design (polling) was accepted initially and only revised after the race condition and demo problem were pointed out. The human review loop caught what the initial generation missed.

### Honest assessment

Claude Code accelerated implementation by roughly 3–4×. The highest-value contributions were the reactive service chains (which are tedious to write correctly by hand), the test cases (which required reasoning about all the mock interactions in a complex service), and the documentation (which benefited from having the full codebase in context). The lowest-value contributions were pure boilerplate (entity classes, repository interfaces) where a code generator would have done the same job. The irreplaceable human contribution was knowing *what* to build, catching design mistakes in review, and making judgment calls on tradeoffs.

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

## Lessons Learned

**1. Reactive programming raises the floor on complexity.**
WebFlux + R2DBC was the right call for LLM latency, but every operation — even a simple "fetch then update" — becomes a reactive chain. Debugging a broken pipeline is harder than debugging a null pointer in imperative code: the error surfaces at subscription time, not at the call site. Next time I would evaluate whether the concurrency requirements actually justify the reactive stack before committing to it.

**2. Escalation correctness is easy to get wrong at the seams.**
The first cut used a polling scheduler (every 15 min) which was both un-demoable and had a race condition: a concurrent decision could arrive between "find candidates" and "mark escalated", causing double-escalation. The fix — re-fetching the step and checking `status=PENDING && escalated_at IS NULL` inside the callback — sounds simple in hindsight but requires thinking about every state transition that could happen between scheduling and firing. Idempotency at the action site, not at the scheduling site, is the right model.

**3. The agent's conversation state storage is a tricky trade-off.**
Storing only text pairs in `conversation_history` keeps the context window compact across many turns, but it means the agent loses visibility into its own past tool calls across sessions. This worked fine for the demo flows but would break any scenario where the agent needs to reason about what it previously fetched or submitted. Persisting the full Anthropic message format (including `tool_use` and `tool_result` blocks) is the correct production choice — the context window cost is real but manageable with summarisation.

**4. Skipping Spring AI was the right call, but added boilerplate.**
Spring AI's tool-calling API had breaking changes mid-development. Calling the Anthropic API directly via WebClient gave full control but meant hand-coding all 14 tool schemas in `ToolDefinitions` and managing the tool-calling loop manually. The boilerplate is manageable, but a stable higher-level abstraction would have been welcome. Worth re-evaluating Spring AI once it reaches GA.

**5. JSONB metadata is powerful but silently forgiving.**
Freeform JSONB on `workflow_instances` means any request type works without schema changes — which is exactly what "generic workflow engine" requires. The downside: a typo in a metadata field name causes a condition to silently evaluate to `false` and skip the step rather than raising an error. Adding per-template JSON Schema validation on submit would catch this at the boundary without sacrificing the generic model.

**6. `instance_steps` as a snapshot is worth the duplication.**
Copying `workflow_steps` into `instance_steps` at submit time gives implicit versioning for free — in-flight requests are unaffected by template edits. The alternative (storing a `template_version_id` and joining at read time) looked cleaner on paper but would have complicated every query that needs step data. The duplication is the right trade at this scale.

**7. Confirmation-before-write is essential for an approval agent.**
The first agent prototype called write tools immediately on user intent. That felt fast but was alarming — a misheard instruction could approve the wrong request. The plan-review loop (present parameters → incorporate feedback → re-present → confirm → execute) adds at least one extra turn per write action but dramatically increases trust. For a workflow tool where decisions can be irreversible, the extra turn is always worth it.
