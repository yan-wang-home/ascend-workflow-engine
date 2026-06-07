# Design Decisions

Architectural choices made during implementation, with rationale and production trade-offs.

---

## 1. Spring WebFlux + R2DBC instead of Spring MVC + JPA

**Choice:** Non-blocking I/O throughout — Spring WebFlux for HTTP, R2DBC for database.

**Why:** The AI agent makes outbound HTTP calls to the Anthropic API which can take several seconds. With a blocking thread-per-request model, each in-flight LLM call holds a thread. WebFlux handles this with a small event-loop thread pool and no stalled threads.

**Trade-off:** No lazy loading, no JPQL — every query is explicit. JSONB mapping requires a custom codec. The reactive programming model has a steeper learning curve. Worth it for this use case given the LLM latency profile.

---

## 2. Stateless JWT auth with BCrypt password hashing

**Choice:** Passwords hashed with `BCryptPasswordEncoder` (cost 10, auto-salted). Authentication is stateless JWT — `JwtUtil` signs tokens with HMAC-SHA256 using a shared secret; `JwtAuthenticationFilter` validates the signature and expiry on every request with no database lookup.

**JWT claims:** `sub` = userId (UUID), `email`, `role`, `iat`, `exp` (24h). The userId UUID is set as the Spring Security principal — controllers receive it via `@AuthenticationPrincipal UUID userId`.

**Why stateless:** No session table, no sticky sessions. Any server instance can validate any token using the shared secret. Scales horizontally for free.

**CSRF disabled:** Correct for a JWT API. CSRF attacks exploit cookies that browsers send automatically; the `Authorization: Bearer` header is never sent automatically, so CSRF is not applicable.

**Trade-offs and production hardening:**
- **BCrypt cost 10 → 12** (or Argon2id per OWASP) — machines are faster than in 2014
- **No token revocation** — logout is client-side only (discard the token). Multiple logins for the same user produce multiple simultaneously valid tokens. Production fix: embed a `jti` (JWT ID) claim at generation time, add a `POST /auth/logout` endpoint that writes `SET blocklist:<jti> 1 EX <remaining-ttl>` to Redis, and check `GET blocklist:<jti>` in the filter before allowing the request through. TTL matches the token's remaining lifetime so entries self-clean with no maintenance
- **HS256 → RS256** — asymmetric signing so verification nodes don't need the private key
- **24h expiry → 15min access token + 7-day refresh token** — shorter-lived access tokens limit the blast radius of a leaked token
- **JWT secret from Secrets Manager** (AWS Secrets Manager / Vault) with rotation, not an env var
- **Delegate auth to an IdP** (Cognito, Auth0, Keycloak) in production — they handle all of the above plus MFA, brute-force protection, and compliance out of the box

---

## 3. ThreadPoolTaskScheduler for escalation instead of a polling job

**Choice:** When a step becomes `PENDING`, schedule an exact-time callback via `ThreadPoolTaskScheduler` stored in a `ConcurrentHashMap<UUID, ScheduledFuture<?>>`. Cancel the future when the step is decided.

**Why:** The polling approach (every 15 min) made live demo impossible — you'd wait up to 15 minutes to see escalation fire. With a scheduled future, setting `timeout_hours=1` and `timeout-multiplier-seconds=60` makes escalation fire in exactly 60 seconds, which is demoable.

**Trade-off:** Not restart-safe — in-memory futures are lost on application restart. Steps that were pending at shutdown need re-scheduling on startup (not implemented; acceptable for this scope). Production path: **Redis `RDelayedQueue` (Redisson)** — persisted, multi-instance safe, O(1) per escalation, survives restarts.

**Idempotency guard:** `doEscalate()` re-fetches the step from the DB and checks `status=PENDING && escalated_at IS NULL` before acting, preventing double-escalation if a concurrent decision arrives just as the timer fires. Escalation fires exactly once per step — the new PENDING step created for the escalation user is not re-scheduled, so there is no infinite escalation chain.

---

## 4. Direct Anthropic REST API instead of Spring AI

**Choice:** Call `api.anthropic.com/v1/messages` directly via WebClient; no Spring AI dependency.

**Why:** Spring AI milestone releases had breaking changes in the tool-calling API that were incompatible with the agentic loop (tool use → results → re-prompt cycle). Direct WebClient gives full control over the message structure and eliminates framework abstraction over the exact JSON the API expects.

**Trade-off:** More boilerplate — tool schemas are hand-coded in `ToolDefinitions`, the loop is hand-written in `AgentService`. Spring AI would reduce this boilerplate once it stabilises.

---

## 5. Agent as a sibling to REST, not a client of it

**Choice:** `AgentToolsService` calls `WorkflowService`, `RequestService`, `ApprovalService` directly — no internal HTTP to our own REST API.

**Why:** Internal HTTP would require re-authenticating (extracting a token, passing it in headers) and introduce an extra network hop. Calling services directly shares the same security context, avoids auth duplication, and keeps the code simpler.

**Trade-off:** The agent and REST controllers must stay in sync — if a service method's signature changes, both callers are affected. This is the right coupling level for a monolith; a micro-service split would revisit this.

---

## 6. Freeform JSONB metadata on workflow_instances

**Choice:** `workflow_instances.metadata` accepts any JSON blob. `step_conditions` evaluate fields within it using string-keyed operators (`EQ`, `GT`, `IN`, etc.).

**Why:** Allows one workflow template to handle purchase orders, expense claims, hiring approvals, and vendor onboarding without any schema changes. The assignment requires "generic" workflows; JSONB delivers that.

**Trade-off:** No compile-time validation of metadata shape. A `submit_request` call with a typo in a field name silently passes; the condition just evaluates to false and the step is skipped rather than raising an error.

**Production hardening:** Add a `metadata_schema JSONB` column to `workflow_templates` (JSON Schema draft-07). On submit, validate the request metadata against it and reject with 400 if it doesn't match. This preserves the generic model while enforcing per-template correctness.

---

## 7. instance_steps as a snapshot of workflow_steps

**Choice:** When a request is submitted, every `workflow_step` is copied into an `instance_step` row. Subsequent edits to the template do not affect in-flight requests.

**Why:** Implicit versioning — the running instance carries its own copy of the step configuration. No `template_version` column needed.

**Trade-off:** Data is duplicated. A template with 10 steps and 1,000 active requests has 10,000 `instance_step` rows. Acceptable at this scale; at very high volume you'd store only a `template_version_id` and snapshot on read.

---

## 8. Stateless synchronous workflow progression

**Choice:** Workflow state advances synchronously inside the `decide()` call — no events, no state machine, no outbox.

**Why:** Simplest correct implementation. The reactive chain in `ApprovalService.advanceWorkflow()` handles APPROVE/REJECT/REQUEST_CHANGES in a single database transaction-equivalent reactive pipeline.

**Trade-off:** No decoupled notifications. If you add email/Slack notifications later, you must modify `ApprovalService` directly. Production path: `ApplicationEventPublisher` for decoupled in-process events (zero-infrastructure, good for notifications); or Temporal for complex orchestration with retries, timeouts, and durable state.

---

## 9. Conversation history stored as text pairs only

**Choice:** `agent_sessions.conversation_history` stores only `[{ "role": "user", "content": "..." }, { "role": "assistant", "content": "..." }]` — not the raw Anthropic message format including tool call traces.

**Why:** Keeps history compact and within Claude's context window across many turns. Tool call traces are already separately persisted in `agent_logs` for observability.

**Trade-off:** The agent cannot reason about its own past tool calls across sessions. If a user asks "what did you approve last time?", Claude can't reconstruct that from the stored history. Production: persist the full Anthropic message format (including `tool_use` and `tool_result` content blocks) so the agent has complete context.

---

## 10. Iterative confirmation before agent write actions

**Choice:** Before executing any write action (`submit_request`, `make_decision`, `create_delegation`, `create_workflow_template`), the agent presents a structured plan with all parameters and invites the user to adjust details. It incorporates changes and re-presents the updated plan until the user explicitly confirms. Only then is the tool called.

**Why:** A single "Shall I proceed?" prompt is easy to blindly confirm. An iterative plan-review loop surfaces all parameters — decision action, comment, delegate, dates — giving the user a chance to catch mistakes or add detail before any mutation happens. Particularly important for approval decisions which may be irreversible.

**Trade-off:** Adds at least one extra conversation turn per write action, sometimes more if the user refines parameters. Acceptable for a workflow tool where correctness matters more than speed.

**Production extension:** Add a user preference flag (`auto_confirm: true`) in `agent_sessions` metadata for power users who want to skip the review loop.

---

## 11. Single-tenant, no org_id

**Choice:** No `org_id` column on any table. All users share a single namespace.

**Why:** Correct scope for the assignment. Multi-tenancy would double the complexity of every query and every security check.

**Production path:** Row-level isolation with `org_id` on all core tables + PostgreSQL Row Level Security (RLS). Each API request sets `SET LOCAL app.current_org_id = ?` and RLS policies enforce the boundary without any application-layer changes.
