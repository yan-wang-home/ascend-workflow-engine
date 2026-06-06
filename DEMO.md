# Demo Script — Ascend Workflow Engine

**Total time:** ~15 minutes  
**Pre-requisites:** Docker running, app started, Postman open

## Setup

```bash
# 1. Start database
docker-compose up -d

# 2. Start the app (normal mode)
./gradlew bootRun

# OR — for live escalation demo (1 timeout_hour = 60 seconds)
APP_ESCALATION_TIMEOUT_HOUR_IN_SECONDS=60 ./gradlew bootRun
```

Open:
- **Swagger UI** → http://localhost:8080/swagger-ui.html
- **Adminer** → http://localhost:8081 (server: `postgres`, db: `ascend_workflow`, user/pass: `postgres`)

---

## Act 1 — Architecture Overview (2 min)

- Open `ARCHITECTURE.md` — walk the system diagram: "REST and agent are siblings; both call the same service layer. No internal HTTP calls."
- In Swagger UI: show the six controller groups — Auth, Workflows, Requests, Approvals, Delegations, Agent.
- In Adminer: show the 13 tables, point to `workflow_instances.metadata` as JSONB — "any request type, zero schema changes."

---

## Act 2 — Create Users and a Workflow Template (3 min)

**Register three users** (POST `/api/v1/auth/register`):

```json
{ "email": "admin@demo.com",    "password": "pass", "name": "Alice Admin",   "role": "ADMIN" }
{ "email": "approver@demo.com", "password": "pass", "name": "Bob Approver",  "role": "APPROVER" }
{ "email": "requester@demo.com","password": "pass", "name": "Carol Requester","role": "REQUESTER" }
```

**Login as admin** (POST `/api/v1/auth/login`) → copy the JWT.

**Create Expense Report workflow** (POST `/api/v1/workflows`):

```json
{
  "name": "Expense Report",
  "description": "Multi-level expense approval",
  "steps": [
    {
      "name": "Manager Review",
      "stepOrder": 1,
      "approverType": "USER",
      "approverId": "<bob-approver-id>",
      "approvalMode": "ANY_OF"
    },
    {
      "name": "Finance Review",
      "stepOrder": 2,
      "approverType": "USER",
      "approverId": "<bob-approver-id>",
      "approvalMode": "ANY_OF",
      "conditions": [{ "fieldName": "amount", "operator": "GT", "value": "5000" }]
    },
    {
      "name": "CFO Approval",
      "stepOrder": 3,
      "approverType": "USER",
      "approverId": "<bob-approver-id>",
      "approvalMode": "ANY_OF",
      "timeoutHours": 1,
      "escalationUserId": "<admin-user-id>",
      "conditions": [{ "fieldName": "amount", "operator": "GT", "value": "10000" }]
    }
  ]
}
```

**Show in Adminer:** `workflow_steps` and `step_conditions` tables — "conditions are stored separately, evaluated at runtime against the JSONB metadata."

---

## Act 3 — Submit Requests (2 min)

**Login as Carol** → copy JWT.

**Submit $3,000 request** (POST `/api/v1/requests`):
```json
{
  "templateId": "<expense-report-id>",
  "title": "Team Dinner — $3,000",
  "metadata": { "amount": 3000, "vendor": "Restaurant", "category": "team_event" }
}
```

**Submit $15,000 request**:
```json
{
  "templateId": "<expense-report-id>",
  "title": "Dell Laptops — $15,000",
  "metadata": { "amount": 15000, "vendor": "Dell", "category": "equipment" }
}
```

**Show in Adminer:** `instance_steps` table — 
- $3,000 request: Steps 2 and 3 are `SKIPPED` (conditions not met), only Step 1 is `PENDING`.
- $15,000 request: All three steps are `PENDING`, but only Step 1 has `started_at` set (the other two wait).

---

## Act 4 — REST Approval Flow (2 min)

**Login as Bob** → copy JWT.

**GET inbox** (`/api/v1/approvals/inbox`) — both requests appear.

**Approve $3,000** (POST `/api/v1/approvals/<instance-id>/decide`):
```json
{ "action": "APPROVE", "comment": "Looks good" }
```

**Show in Adminer:**
- `workflow_instances`: status = `APPROVED` (Steps 2+3 were SKIPPED, so it completed immediately).
- `decisions`: the recorded decision.
- `audit_trail`: `DECISION_MADE` + `REQUEST_SUBMITTED` events.

---

## Act 5 — AI Agent (4 min) ← CENTERPIECE

**Login as Bob.**

**Turn 1 — Read:**  
POST `/api/v1/agent/chat`
```json
{ "message": "Show my pending approvals" }
```
Claude calls `get_pending_approvals`, returns the Dell Laptops request.

**Turn 2 — Intent:**  
```json
{ "message": "Approve the Dell equipment request" }
```
Claude presents a structured plan: "I'm about to approve: Dell Laptops — $15,000, decision: APPROVE, comment: none. Does this look right, or would you like to change anything?"

**Turn 3 — Refine:**  
```json
{ "message": "Add comment: approved by finance team" }
```
Claude updates the plan: "Updated — comment: 'approved by finance team'. Shall I proceed?"

**Turn 4 — Confirm:**  
```json
{ "message": "Yes" }
```
Claude calls `make_decision` → "Done. The Dell Laptops request has been approved."

**Show in Adminer:** `agent_logs` — expand the `tool_calls` JSONB column. Point out `name`, `input`, `result`, and `duration_ms` for full observability.

---

## Act 6 — Live Escalation Demo (2 min) ← NEW

> **Requires:** app started with `APP_ESCALATION_TIMEOUT_HOUR_IN_SECONDS=60`

**Create a short-timeout template** with `timeoutHours: 1` and an escalation user set (as shown in Act 2, Step 3 above).

**Submit a request** → leave it unapproved.

**Wait 60 seconds.**

**Refresh Adminer `instance_steps`:**
- Original step: `ESCALATED`, `escalated_at` is set, `escalated_to_user_id` = Admin's UUID.
- New row: `PENDING`, `started_at` = now — the escalated step in Admin's inbox.

**Check Bob's inbox** → escalated item is now gone.  
**Check Admin's inbox** → the step appears, ready for Admin to decide.

**Cancel path:** Submit another request, approve it within 60 seconds → no escalation fires (the timer is cancelled on decision).

---

## Act 7 — Delegation (1 min)

**Login as Bob** → create a delegation (POST `/api/v1/delegations`):
```json
{
  "delegateId": "<admin-user-id>",
  "templateId": "<expense-report-id>",
  "startsAt": "2026-01-01T00:00:00Z",
  "endsAt": "2026-12-31T23:59:59Z"
}
```

**Login as Admin** → GET inbox — the request Bob should approve now appears in Admin's inbox too (merged without duplicates).

---

## Act 8 — Wrap Up (1 min)

- GitHub repo: `README.md` (quick start), `SOLUTION.md` (technical write-up), `ARCHITECTURE.md` (three Mermaid diagrams), `DESIGN_DECISIONS.md` (architectural trade-offs).
- "All three parts of the assignment are implemented: SQL data model with 13 tables, 24+ REST endpoints documented in Swagger, and an agentic assistant with an 8-tool calling loop, confirmation before writes, and full observability via agent_logs."
