-- ============================================================
-- Users & Groups
-- ============================================================

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    role            VARCHAR(50)  NOT NULL CHECK (role IN ('ADMIN', 'APPROVER', 'REQUESTER')),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- user_groups avoids the reserved word "groups"
CREATE TABLE user_groups (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE group_members (
    group_id UUID NOT NULL REFERENCES user_groups(id) ON DELETE CASCADE,
    user_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, user_id)
);

-- ============================================================
-- Workflow Templates (blueprints)
-- ============================================================

CREATE TABLE workflow_templates (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_by  UUID NOT NULL REFERENCES users(id),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE workflow_steps (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id          UUID NOT NULL REFERENCES workflow_templates(id) ON DELETE CASCADE,
    step_order           INT  NOT NULL,
    -- steps sharing the same parallel_group at the same step_order run simultaneously
    parallel_group       INT,
    name                 VARCHAR(255) NOT NULL,
    approver_type        VARCHAR(50)  NOT NULL CHECK (approver_type IN ('USER', 'GROUP', 'ROLE')),
    approver_id          VARCHAR(255) NOT NULL,
    approval_mode        VARCHAR(50)  NOT NULL CHECK (approval_mode IN ('ANY_OF', 'ALL_OF')),
    timeout_hours        INT,
    escalation_user_id   UUID REFERENCES users(id),
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE step_conditions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    step_id     UUID        NOT NULL REFERENCES workflow_steps(id) ON DELETE CASCADE,
    field_name  VARCHAR(255) NOT NULL,
    operator    VARCHAR(50)  NOT NULL CHECK (operator IN ('EQ', 'NEQ', 'GT', 'GTE', 'LT', 'LTE', 'IN', 'CONTAINS')),
    value       VARCHAR(255) NOT NULL
);

-- ============================================================
-- Workflow Instances (runtime)
-- ============================================================

CREATE TABLE workflow_instances (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id         UUID         NOT NULL REFERENCES workflow_templates(id),
    requester_id        UUID         NOT NULL REFERENCES users(id),
    title               VARCHAR(500) NOT NULL,
    status              VARCHAR(50)  NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    -- flexible payload: amount, vendor, description, etc. — no schema change per request type
    metadata            JSONB        NOT NULL DEFAULT '{}',
    current_step_order  INT          NOT NULL DEFAULT 1,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE instance_steps (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id             UUID NOT NULL REFERENCES workflow_instances(id) ON DELETE CASCADE,
    step_id                 UUID NOT NULL REFERENCES workflow_steps(id),
    step_order              INT  NOT NULL,
    parallel_group          INT,
    status                  VARCHAR(50) NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'SKIPPED', 'ESCALATED')),
    started_at              TIMESTAMP WITH TIME ZONE,
    completed_at            TIMESTAMP WITH TIME ZONE,
    -- escalation tracking: prevents double-escalation by the scheduler
    escalated_at            TIMESTAMP WITH TIME ZONE,
    escalated_to_user_id    UUID REFERENCES users(id)
);

CREATE TABLE decisions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_step_id  UUID        NOT NULL REFERENCES instance_steps(id),
    approver_id       UUID        NOT NULL REFERENCES users(id),
    action            VARCHAR(50) NOT NULL CHECK (action IN ('APPROVE', 'REJECT', 'REQUEST_CHANGES')),
    comment           TEXT,
    decided_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- ============================================================
-- Delegations
-- ============================================================

CREATE TABLE delegations (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delegator_id  UUID NOT NULL REFERENCES users(id),
    delegate_id   UUID NOT NULL REFERENCES users(id),
    -- null = delegate authority across all workflow templates
    template_id   UUID REFERENCES workflow_templates(id),
    starts_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_delegation_dates CHECK (ends_at > starts_at),
    CONSTRAINT chk_no_self_delegation CHECK (delegator_id <> delegate_id)
);

-- ============================================================
-- Audit Trail (append-only)
-- ============================================================

CREATE TABLE audit_trail (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- nullable: some audit events are system-level (e.g. escalation)
    instance_id  UUID REFERENCES workflow_instances(id),
    user_id      UUID NOT NULL REFERENCES users(id),
    action       VARCHAR(255) NOT NULL,
    details      JSONB NOT NULL DEFAULT '{}',
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- ============================================================
-- Agent Sessions & Logs
-- ============================================================

CREATE TABLE agent_sessions (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID  NOT NULL REFERENCES users(id),
    conversation_history JSONB NOT NULL DEFAULT '[]',
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE agent_logs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          UUID  NOT NULL REFERENCES agent_sessions(id),
    user_message        TEXT  NOT NULL,
    -- full trace: tool name, input, output, success/error per invocation
    tool_calls          JSONB NOT NULL DEFAULT '[]',
    assistant_response  TEXT,
    duration_ms         BIGINT,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- ============================================================
-- Indexes
-- ============================================================

CREATE INDEX idx_workflow_steps_template        ON workflow_steps(template_id);
CREATE INDEX idx_step_conditions_step           ON step_conditions(step_id);

CREATE INDEX idx_instances_requester            ON workflow_instances(requester_id);
CREATE INDEX idx_instances_template             ON workflow_instances(template_id);
CREATE INDEX idx_instances_status               ON workflow_instances(status);

CREATE INDEX idx_instance_steps_instance        ON instance_steps(instance_id);
CREATE INDEX idx_instance_steps_status          ON instance_steps(status);
-- used by escalation scheduler: find PENDING steps with no escalation yet
CREATE INDEX idx_instance_steps_escalation      ON instance_steps(status, escalated_at, started_at)
    WHERE status = 'PENDING' AND escalated_at IS NULL;

CREATE INDEX idx_decisions_step                 ON decisions(instance_step_id);
CREATE INDEX idx_decisions_approver             ON decisions(approver_id);

CREATE INDEX idx_delegations_delegator          ON delegations(delegator_id);
CREATE INDEX idx_delegations_delegate           ON delegations(delegate_id);
CREATE INDEX idx_delegations_active             ON delegations(delegate_id, is_active, starts_at, ends_at);

CREATE INDEX idx_audit_trail_instance           ON audit_trail(instance_id);
CREATE INDEX idx_audit_trail_user               ON audit_trail(user_id);
CREATE INDEX idx_audit_trail_created            ON audit_trail(created_at);

CREATE INDEX idx_agent_sessions_user            ON agent_sessions(user_id);
CREATE INDEX idx_agent_logs_session             ON agent_logs(session_id);
CREATE INDEX idx_agent_logs_created             ON agent_logs(created_at);
