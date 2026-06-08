# Ascend Approval Workflow Engine

A generic, AI-powered approval workflow engine built with Java 21, Spring WebFlux, and the Anthropic API.

## Prerequisites

- Java 21+ (tested with JDK 25/26)
- Docker (to run PostgreSQL)
- An Anthropic API key

## Quick Start

**1. Start PostgreSQL**
```bash
docker-compose up -d
```

**2. Set environment variables**
```bash
export ANTHROPIC_API_KEY=sk-ant-...
export JWT_SECRET=your-256-bit-secret   # optional, has a default
```

**3. Run the application**
```bash
./gradlew bootRun
```

The server starts on **http://localhost:8080**. Flyway runs migrations automatically on startup.

**Swagger UI:** http://localhost:8080/swagger-ui.html  
**OpenAPI JSON:** http://localhost:8080/api-docs

## Running Tests

```bash
./gradlew test
```

Unit tests run without a database. The integration agent test (`MultiTurnAgentTest`) requires `ANTHROPIC_API_KEY` to be set and is skipped otherwise.

## Project Structure

```
src/main/java/com/ascend/workflow/
├── api/                  # REST controllers + DTOs + global exception handler
├── config/               # Security, R2DBC, OpenAPI configuration
├── domain/
│   ├── model/            # R2DBC entity models
│   └── service/          # Business logic (approval, escalation, delegation, etc.)
└── infrastructure/
    ├── ai/               # Anthropic API client, tool definitions, agent loop
    ├── repository/       # Spring Data R2DBC repositories
    └── security/         # JWT filter and utility
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `ANTHROPIC_API_KEY` | _(required for AI chat)_ | Anthropic API key |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `ascend_workflow` | Database name |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | `postgres` | Database password |
| `JWT_SECRET` | _(built-in default)_ | HS256 signing secret (min 256 bits) |
| `APP_ESCALATION_TIMEOUT_HOUR_IN_SECONDS` | `3600` | Seconds per `timeoutHours` unit. Set to `60` to make 1-hour escalation fire in 60 seconds for demos |

## Demo with Postman

Import both files from the `postman/` directory:
1. `Ascend_Approval_Workflow.postman_collection.json` — all API requests
2. `Ascend_Local.postman_environment.json` — local environment variables

The collection auto-captures the JWT token after login and propagates `requestId`, `templateId`, and other IDs between requests.

## Resetting the Database

`cleanup.sql` in the repo root truncates all data tables in FK-safe order, re-seeds the system user, and inserts all five demo users (`admin@ascend.com`, `manager@ascend.com`, `finance@ascend.com`, `vp@ascend.com`, `requester@ascend.com`) with their correct roles and password `password123`. Schema and Flyway migration history are preserved.

```bash
psql -U postgres -d ascend_workflow < cleanup.sql
# or via Docker:
docker exec -i ascend-postgres-1 psql -U postgres -d ascend_workflow < cleanup.sql
```
