# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Start PostgreSQL (required before running the app)
docker-compose up -d

# Compile
./gradlew compileJava

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.ascend.workflow.service.ConditionEvaluatorTest"

# Run the application
./gradlew bootRun

# Build fat JAR
./gradlew bootJar
```

Required environment variables:
- `ANTHROPIC_API_KEY` — Anthropic API key (app starts without it but agent chat fails at runtime)
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` — all default to localhost/5432/ascend_workflow/postgres/postgres

## Java Version Notes

The machine runs JDK 25/26. The project targets Java 21 via `sourceCompatibility` (not toolchain — toolchain would fail looking for an exact JDK 21 install). Lombok is pinned to `1.18.38` for Java 26 compatibility. Tests use `mock-maker-subclass` (in `src/test/resources/mockito-extensions/`) because Mockito's inline mock maker cannot instrument classes on Java 26.

## Architecture

### Layer structure

```
api/                    REST controllers + DTOs + GlobalExceptionHandler
config/                 SecurityConfig, R2dbcConfig, OpenApiConfig
domain/
  model/                R2DBC entities (no JPA — use Spring Data R2DBC annotations)
  service/              All business logic lives here
infrastructure/
  ai/                   Anthropic integration (AnthropicClient, AgentService, ToolDefinitions, AgentToolsService)
  repository/           Spring Data R2DBC repositories
  security/             JwtUtil, JwtAuthenticationFilter
```

### Key architectural decisions

**Reactive stack throughout.** Everything returns `Mono<T>` or `Flux<T>`. Blocking calls inside `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` are used only in `AgentService` for the synchronous Anthropic API loop.

**REST API and agent are siblings.** Both call the service layer directly — the agent does not make internal HTTP calls to the REST endpoints. Adding a new capability means adding it to the service layer and optionally exposing it via both a controller and a tool in `AgentToolsService`.

**No JPA on the classpath.** Use `ResourceNotFoundException` (in `domain/service/`) instead of `jakarta.persistence.EntityNotFoundException`. Entity classes use `@Table`, `@Id`, `@Column` from Spring Data R2DBC, not JPA annotations.

**JSONB columns** (`metadata` on `workflow_instances`, `conversation_history` on `agent_sessions`, `tool_calls` on `agent_logs`) use `io.r2dbc.postgresql.codec.Json` as the field type. Read with `.asString()`, write with `Json.of(string)`.

### Workflow execution flow

1. `WorkflowService.create()` saves a template + its steps + step conditions.
2. `RequestService.submit()` instantiates the template: copies `workflow_steps` into `instance_steps` for the current step order, evaluating `step_conditions` against `metadata` — steps whose conditions don't match are auto-`SKIPPED`.
3. `ApprovalService.decide()` records a `Decision`, then calls `advanceWorkflow()`:
   - `REJECT` → closes the instance immediately.
   - `APPROVE` → checks if all `instance_steps` sharing the same `parallel_group` are `APPROVED`/`SKIPPED`; if yes, activates the next `step_order`.
4. `EscalationScheduler` runs every 15 min, finds `PENDING` steps past `timeout_hours` with `escalated_at IS NULL`, marks them `ESCALATED`, and creates a new `PENDING` step for the `escalation_user_id`. The `escalated_at` timestamp is the idempotency guard preventing double-escalation.

### Agent tool-calling loop (`AgentService`)

`runAgentLoop()` calls `AnthropicClient.chat()` up to 10 times per user message:
1. Build messages array from persisted conversation history + current user message.
2. Call Claude with all 8 tool schemas from `ToolDefinitions`.
3. If `stop_reason == "tool_use"`, dispatch each tool via `AgentToolsService.dispatch()` (switch on tool name → service call → JSON string result), append results as a user turn, repeat.
4. On text response, persist the turn to `agent_sessions.conversation_history` (text-only, not raw tool traces) and log the full tool call trace to `agent_logs`.

### Security

JWT is stateless. `JwtAuthenticationFilter` reads the `Bearer` token, extracts `userId` (UUID) as the principal and `role` with `ROLE_` prefix as the authority. Controllers extract `userId` via `@AuthenticationPrincipal`. Role-based access: `ADMIN`, `APPROVER`, `REQUESTER`. Only `GET /api/v1/agent/logs` requires `ADMIN`; all other non-auth endpoints require authentication only.

### Adding a new agent tool

1. Add the tool schema to `ToolDefinitions.all()`.
2. Add a `case` branch in `AgentToolsService.dispatch()` that calls the appropriate service method.
3. The tool is automatically available to Claude on the next chat request — no other wiring needed.
