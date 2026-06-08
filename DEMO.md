# Demo Script — Ascend Workflow Engine

**Total time:** ~30 minutes  
**Pre-requisites:** Docker running, app started with escalation demo config, Postman open with `Ascend_Local` environment selected

## Users

| Name | Email | Role |
|------|-------|------|
| Admin User | admin@ascend.com | ADMIN |
| Alice Manager | manager@ascend.com | APPROVER |
| Bob Finance | finance@ascend.com | APPROVER |
| Carol VP | vp@ascend.com | APPROVER |
| Jane Requester | requester@ascend.com | REQUESTER |

## Setup

```bash
# 1. Start database
docker-compose up -d

# 2. Start the app with live-escalation config (1 timeout_hour = 60 seconds)
APP_ESCALATION_TIMEOUT_HOUR_IN_SECONDS=60 ./gradlew bootRun
```

Open:
- **Postman** → import both files from `postman/`, select `Ascend Local` environment
- **Swagger UI** → http://localhost:8080/swagger-ui.html
- **Adminer** → http://localhost:8081 (server: `postgres`, db: `ascend_workflow`, user/pass: `postgres`)

> **Tip:** Run sections in order. Each "Login as X" request inside a section automatically updates `{{token}}` for the requests that follow. On repeat runs, skip the Register requests and start from the Login requests — each repopulates its `_id` variable.

---

## Act 1 — Architecture Overview (2 min)

- Open `DIAGRAMS.md` — start with diagram 0 (Overall User Flow) for the big picture: Admin sets up once, Requester submits, Approvers decide, AI Agent can drive all three roles. Then walk diagrams 1a–1e (one per supported workflow type). Then diagram 2 (System Architecture) and diagram 3 (Agent Layer). During Acts 5–9, pull up the matching diagram (1b for Parallel, 1c for Group, 1d for Delegation, 1e for Escalation) as you demo each flow.
- In Swagger UI: show the seven controller groups — Auth, Workflows, Requests, Approvals, Delegations, **Groups**, Agent.
- In Adminer: show the 13 tables. Point to `workflow_instances.metadata` (JSONB) — "any request type with zero schema changes." Point to `step_conditions` — "conditions are evaluated at runtime against this metadata."

---

## Act 2 — Auth & Workflow Template (3 min)

**Postman section 1 — Auth** (first time only; skip Register on repeat runs)

1. **Register Admin** → captures `{{admin_id}}`
2. **Register Manager**, **Register Finance**, **Register VP** → captures `{{manager_id}}`, `{{finance_id}}`, `{{vp_id}}`
3. **Register Requester** → captures `{{requester_id}}`
4. **Login as Admin** → captures `{{token}}`

**Postman section 2 — Workflow Administration**

1. **Login as Admin**
2. **Create Expense Report Workflow** → captures `{{template_id}}`  
   Three conditional steps:
   - Step 1 — **Manager Approval** (Alice): always runs
   - Step 2 — **Finance Approval** (Bob): only when `amount > 5,000`
   - Step 3 — **VP Approval** (Carol): only when `amount > 10,000`; 48-hour timeout, escalates to Admin
3. **Get Workflow by ID** — response includes full `steps` + `conditions` arrays.

**Show in Adminer:** `workflow_steps` + `step_conditions` — conditions stored separately, evaluated per-request at submit time.

---

## Act 3 — Normal Flow (6 min)

**Postman section 3 — Normal Flow**

**Submit requests:**

1. **Login as Requester**
2. **Submit $3,000 Request** → captures `{{request_id}}`
3. **Submit $7,000 Request** → captures `{{medium_request_id}}`
4. **Submit $15,000 Request** → captures `{{big_request_id}}`

**Show in Adminer:** `instance_steps` —
- **$3,000:** Steps 2 and 3 are `SKIPPED` (amount ≤ $5k, ≤ $10k). Only Step 1 is `PENDING`.
- **$7,000:** Steps 1 and 2 are `PENDING`; Step 3 is `SKIPPED`.
- **$15,000:** All three steps are `PENDING`, but only Step 1 has `started_at` set — others wait their turn.

Point out: "The same template produces three completely different execution paths based on JSONB metadata. Zero code changes."

**Run the approval chain** — the $15k chain and $7k REQUEST_CHANGES loop are interleaved:

1. **Login as Manager** → **Get Inbox** — sees all three requests at Step 1.
2. **Approve $3,000** — closes immediately; Steps 2+3 were SKIPPED.
3. **Request Changes on $7,000** — instance status → `CHANGES_REQUESTED`; step stays `PENDING` so the same approver re-reviews after requester updates it.
4. **Approve $15,000 — Step 1** — Manager sign-off; instance advances to Finance.
5. **Login as Requester** → **List My Requests** — $7k shows `CHANGES_REQUESTED`.
6. **Resubmit $7,000** (`POST /requests/{id}/resubmit`) with updated metadata adding `vendorQuote` and `justification` → status flips back to `PENDING`.
7. **Login as Manager** → **Get Inbox** — $7k reappears. → **Approve $7,000 — Step 1** → advances to Finance.
8. **Login as Finance** → **Get Inbox** — sees $15k at Step 2 and $7k at Step 2.
9. **Approve $15,000 — Step 2** → advances to VP.
10. **Approve $7,000 — Step 2** → closes `APPROVED` (Step 3 was SKIPPED, amount ≤ $10k).
11. **Login as VP** → **Approve $15,000 — Step 3** → workflow closes `APPROVED`.

**Show in Adminer:**
- `workflow_instances`: $7k transitions `PENDING` → `CHANGES_REQUESTED` → `PENDING` → `APPROVED`.
- `decisions`: two rows on the same $7k step — `REQUEST_CHANGES` then `APPROVE`.
- `audit_trail`: `DECISION_MADE` → `REQUEST_RESUBMITTED` → `DECISION_MADE` sequence.

---

## Act 4 — Delegation (2 min)

**Postman section 4 — Delegation**

A fresh $2,500 travel request is submitted so Manager has a pending item to delegate (Act 3 approved everything).

1. **Login as Requester** → **Submit Delegation Test Request** → captures `{{delegation_request_id}}`
2. **Login as Manager** → **Create Delegation** (Manager → VP, covers while out of office) → captures `{{delegation_id}}`
3. **Login as VP** → **Get Inbox as Delegate** — the travel request appears in VP's inbox even though VP is not the direct approver on that step.
4. **VP Approves Delegated Travel Request** — decision is recorded under VP's userId.
5. **Login as Manager** → **Revoke Delegation** — delegation removed.

Point out: "The inbox query merges direct approvals, group memberships, and active delegations into one deduplicated list — single `UNION` query, no duplication."

---

## Act 5 — Group-Based Approval (3 min)

**Postman section 5 — Group Approval**

Finance and VP are both members of "Finance Team". VP approves — demonstrating that any group member can decide, not just a named individual.

1. **Login as Admin** → **List Groups** (empty initially) → **Create Group 'Finance Team'** → captures `{{group_id}}`
2. **Add Finance to Group**, **Add VP to Group**
3. **Create Group-Approval Workflow** — step uses `approverType: GROUP`, `approverId: {{group_id}}`
4. **Login as Requester** → **Submit Group-Approval Request** → captures `{{group_request_id}}`
5. **Login as VP** → **Get Inbox** — group-assigned request appears (VP is a Finance Team member).
6. **Approve as VP** — any group member may decide. Workflow closes.

**Show in Adminer:** `group_members` + `user_groups` — "swap individuals in/out of the group without touching the workflow template."

---

## Act 6 — Parallel Approval (3 min)

**Postman section 6 — Parallel Approval**

Manager and Finance must both approve simultaneously (`step_order=1`, `parallel_group=1`). VP's final confirmation only activates after both complete.

1. **Login as Admin** → **Create Parallel-Approval Workflow** → captures `{{parallel_template_id}}`  
   Step 1A: Manager | Step 1B: Finance (parallel) | Step 2: VP (final)
2. **Login as Requester** → **Submit Parallel Request** → captures `{{parallel_request_id}}`
3. **Login as Manager** → **Approve Step 1A** — Finance 1B still pending; VP Step 2 not yet active.
4. **Login as Finance** → **Approve Step 1B** — parallel group complete; Step 2 activates automatically.
5. **Login as VP** → **Get Inbox** (Step 2 now active) → **Approve Final Step** → workflow closes `APPROVED`.

**Show in Adminer:** `instance_steps` after Step 1A — Step 1B still `PENDING`. After Step 1B — both `APPROVED`, Step 2 flips to `PENDING`.

---

## Act 7 — Live Escalation (2 min)

> **Requires:** app running with `APP_ESCALATION_TIMEOUT_HOUR_IN_SECONDS=60`

**Postman section 7 — Escalation Demo**

1. **Login as Admin** → **Create Escalation Workflow** (`timeoutHours=1` → fires in 60s with demo config; escalates to VP)
2. **Login as Requester** → **Submit Escalation Request** → captures `{{escalation_request_id}}`
3. **⏱ Wait 60 seconds** — do not approve.
4. **Check Request** (GET) — original step shows `status: ESCALATED`.
5. **Login as VP** → **Get Inbox** — escalated step now appears assigned to VP.
6. **Approve Escalated Step** → workflow closes `APPROVED`.

**Show in Adminer:** `instance_steps` —
- Original row: `status = ESCALATED`, `escalated_at` set.
- New row: `status = PENDING`, `started_at` = escalation time — VP's inbox entry.

**Cancel path:** Submit another request, approve it within 60 seconds → no escalation fires (the `ThreadPoolTaskScheduler` future is cancelled on decision).

---

## Act 8 — AI Agent (8 min) ← CENTERPIECE

**Postman section 8 — Agent Chat**

The agent covers the full workflow lifecycle through natural language across five subsections. Each subsection starts fresh — run them in order or independently.

> **Key talking point:** "The agent calls the same service layer as the REST API directly — no internal HTTP calls, no duplicated auth. Adding a new capability means one service method + one tool schema."

### 8a. Normal Flow — Create Template, Submit, Approve

1. **Login as Admin** → **Create Equipment Purchase Workflow** (2 turns) — Claude calls `list_users` to resolve Finance and VP by name, then calls `create_workflow_template`. Presents a plan, waits for confirmation.
2. **Login as Requester** → **Submit Laptop Request** (3 turns) — Claude calls `list_workflow_templates`, collects details, presents the plan, submits on confirmation.
3. **Login as Finance** → **Review Inbox + Approve** (2 turns) — Claude calls `get_pending_approvals`, identifies the request, presents the decision, calls `make_decision` on confirmation.
4. **Login as VP** → **Approve** (2 turns) — same pattern.
5. **Login as Requester** → **Check Final Status** — Claude calls `get_request_details`, returns `APPROVED`.

### 8b. Delegation — Manager Delegates to VP

1. **Login as Admin** → **Create Travel Approval Workflow** (Manager as sole approver).
2. **Login as Requester** → **Submit Travel Request**.
3. **Login as Manager** → **Delegate to VP** (7 days) — Claude calls `list_users`, `create_delegation`. Plan + confirm.
4. **Login as VP** → **Review Inbox** (delegated request appears) → **Approve**.
5. **Login as Manager** → **List Delegations** → **Revoke** — Claude calls `list_delegations`, then `revoke_delegation`.

### 8c. Group Approval — ALL_OF Finance Committee

1. **Login as Admin** → **Create Finance Committee Group** — Claude calls `list_users`, `create_group`, `add_group_member` × 2. Plan + confirm.
2. **Admin** → **Create Capital Expenditure Workflow** (`approvalMode: ALL_OF`) — Claude calls `list_groups` to resolve group UUID.
3. **Login as Requester** → **Submit CapEx Request** ($25,000 servers).
4. **Login as Finance** → **Approve** — step stays open; VP still pending.
5. **Login as VP** → **Approve** — both members approved; workflow closes `APPROVED`.

**Key point:** With `ALL_OF`, one approval is not enough — both group members must approve.

### 8d. Parallel Approval — Manager & Finance in Parallel, then VP

1. **Login as Admin** → **Create Parallel Workflow** — Claude sets the same `stepOrder` and a shared `parallelGroup` for Manager and Finance, VP at step 2.
2. **Login as Requester** → **Submit Software License Request** ($8,000).
3. **Login as Manager** → **Approve** — Finance still pending; VP step not yet active.
4. **Login as Finance** → **Approve** — parallel group complete; VP step activates automatically.
5. **Login as VP** → **Final Approval** — workflow closes.

### 8e. Escalation — Times Out to VP (60s with demo config)

> **Requires:** app running with `APP_ESCALATION_TIMEOUT_HOUR_IN_SECONDS=60`

1. **Login as Admin** → **Create Urgent Purchase Workflow** — Manager approver, `timeoutHours: 1`, escalate to VP. Claude calls `list_users` to resolve both UUIDs.
2. **Login as Requester** → **Submit Urgent Request**.
3. **⏱ Wait ~60 seconds** — do not approve as Manager.
4. **Login as VP** → **Review Escalated Inbox** — original step is `ESCALATED`; a new `PENDING` step for VP appears. → **Approve** → workflow closes.

**Show in Adminer:** `instance_steps` — one `ESCALATED` row (original), one `APPROVED` row (VP's step). Escalation fires exactly once.

### Admin Logs

**Login as Admin** → **Get Agent Logs** — `tool_calls` JSONB column shows every tool name, input, result, and `duration_ms` for full observability across all five flows.

---

## Act 9 — Wrap Up (1 min)

- GitHub repo: `README.md` (quick start), `SOLUTION.md` (technical write-up), `DIAGRAMS.md` (four Mermaid diagrams), `DESIGN_DECISIONS.md` (architectural trade-offs).
- "All three parts of the assignment are implemented and demoed: 13-table SQL schema, 25+ REST endpoints across 7 controllers, and an agentic assistant with a 14-tool calling loop — covering the full workflow lifecycle including group-based approvals, parallel approvals, live escalation, request-changes loop, and delegation — all drivable through natural language."
