# Solution Overview

## Part 1 — Data Model

**Tech:** PostgreSQL · Spring Data R2DBC · Flyway (versioned SQL migrations) · JSONB for flexible metadata

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

**Tech:** Spring WebFlux (reactive controllers) · Spring Security + JWT (stateless auth) · RBAC (three roles) · Springdoc OpenAPI / Swagger UI

Six controllers expose 24+ endpoints, all documented in Swagger UI.

### Endpoints by domain

**Auth** — `POST /api/v1/auth/register` (defaults to `REQUESTER`; an `ADMIN` caller can assign any role via the `role` field — non-admin callers requesting a higher role receive 403), `POST /api/v1/auth/login`

**Workflows (templates)** — CRUD for workflow templates and their steps. Only `ADMIN` users can create or modify templates.

**Requests** — `POST /api/v1/requests` submits a new approval request (instantiates a template). `GET /api/v1/requests` lists the caller's own requests with status filtering.

**Approvals** — `GET /api/v1/approvals/inbox` returns all pending items for the current user, merging direct approvals, group-based approvals, and delegated authority into one deduplicated list. `POST /api/v1/approvals/{requestId}/decide` records a decision and advances the workflow.

**Delegations** — `POST /api/v1/delegations` creates a delegation (optionally scoped to a single template). `GET /api/v1/delegations` lists the caller's own delegations. `DELETE /api/v1/delegations/{id}` revokes one.

**Agent** — `POST /api/v1/agent/chat` (multi-turn AI assistant), `GET /api/v1/agent/logs` (admin only).

### Authentication & Authorization

**JWT token claims**

| Claim | Value |
|---|---|
| `sub` | userId (UUID) — set as the Spring Security principal; controllers receive it via `@AuthenticationPrincipal` |
| `email` | User's email address |
| `role` | One of `ADMIN`, `APPROVER`, `REQUESTER` |
| `iat` / `exp` | Issued-at / expiry (24 hours) |

Tokens are signed with HMAC-SHA256. `JwtAuthenticationFilter` validates the signature and expiry on every request with no database lookup — fully stateless.

**RBAC — three roles**

| Role | Permissions |
|---|---|
| `ADMIN` | Register users with any role, full CRUD on workflow templates and groups, view agent logs (`GET /agent/logs`) |
| `APPROVER` | Everything `REQUESTER` can do, plus make decisions (`POST /approvals/{id}/decide`) on assigned steps |
| `REQUESTER` | Submit requests, view own requests, use the AI agent |

All non-auth endpoints require a valid JWT. Role checks are enforced by Spring Security config on the `role` claim — no secondary database lookup. The `role` field on `POST /auth/register` is only honoured when the caller presents an `ADMIN` JWT; unauthenticated callers always receive `REQUESTER`.

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

**Tech:** Anthropic API (Claude) · WebClient (non-blocking HTTP) · tool-calling loop (up to 10 iterations) · JSONB conversation history

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

## Production Readiness TODO

### Security
- [ ] **JWT revocation** — add `jti` claim at generation time; `POST /auth/logout` writes `SET blocklist:<jti> 1 EX <remaining-ttl>` to Redis; filter checks `GET blocklist:<jti>` before allowing the request
- [ ] **Refresh tokens** — replace 24h access token with 15min access token + 7-day refresh token to limit blast radius of a leaked token
- [ ] **JWT secret rotation** — load secret from AWS Secrets Manager / HashiCorp Vault with rotation; remove the env-var fallback
- [ ] **Delegate auth to an IdP** — Cognito, Auth0, or Keycloak handles MFA, brute-force protection, SSO, and compliance out of the box
- [ ] **Rate limiting** — apply on `POST /auth/login` and `POST /auth/register` to prevent credential stuffing

### Multi-tenancy
- [ ] **Row-level isolation** — add `tenant_id UUID NOT NULL` to all core tables; scope every query with `AND tenant_id = :tenantId`; alternatively use PostgreSQL Row Level Security with `SET LOCAL app.current_org_id = ?` so the DB enforces the boundary without application-layer changes
- [ ] **Tenant onboarding API** — `POST /api/v1/tenants` (platform admin only) to provision a new tenant and its first admin user

### Notifications
- [ ] **Step activation** — notify the assigned approver (email or webhook) when an `instance_step` becomes `PENDING`
- [ ] **Escalation** — notify both the original approver and the escalation target when a step is escalated

### Agent
- [ ] **Persist full message format** — store the complete Anthropic message format (including `tool_use` and `tool_result` blocks) in `conversation_history` so the agent can reason about its own past tool calls across sessions
- [ ] **Context management** — add token budget tracking and conversation summarisation for long-running sessions to avoid hitting the context window limit
- [ ] **Re-evaluate Spring AI** — the library had breaking changes during development; revisit once it reaches GA for a cleaner tool-calling abstraction

### Database
- [ ] **Metadata validation** — enforce per-template JSON Schema on `POST /requests` to catch metadata field typos at the boundary (currently a typo causes a condition to silently evaluate `false` and skip the step)
- [ ] **Connection pool tuning** — configure R2DBC pool `initial-size`, `max-size`, and `max-idle-time` for expected production concurrency
- [ ] **Backups and PITR** — enable continuous WAL archiving and point-in-time recovery; define RTO/RPO targets

### Observability
- [ ] **Structured logging** — switch to JSON log output (Logback + `logstash-logback-encoder`) for log aggregation pipelines
- [ ] **Metrics** — add Micrometer + Prometheus: request latency per endpoint, agent turn duration, escalation rate, approval decision rate
- [ ] **Distributed tracing** — instrument with OpenTelemetry; propagate trace IDs through the agent tool-calling loop
- [ ] **Health endpoints** — expose Spring Actuator `/actuator/health` and `/actuator/info`; wire into load balancer health checks

### Infrastructure / CI-CD
- [ ] **Production Dockerfile** — multi-stage build (compile → slim JRE image); separate from the local `docker-compose.yml` which is DB-only
- [ ] **CI pipeline** — compile + test + static analysis on every pull request
- [ ] **CD pipeline** — build and push Docker image on merge to main; deploy to staging automatically, production on manual approval
- [ ] **Secrets management** — move `ANTHROPIC_API_KEY`, `JWT_SECRET`, and DB credentials out of env vars into a secrets manager; inject at runtime

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
