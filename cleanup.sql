-- Wipes all data in FK-safe order. Schema and Flyway migrations are preserved.
TRUNCATE TABLE
    agent_logs,
    agent_sessions,
    audit_trail,
    decisions,
    delegations,
    instance_steps,
    workflow_instances,
    step_conditions,
    workflow_steps,
    workflow_templates,
    group_members,
    user_groups,
    users
RESTART IDENTITY CASCADE;

-- Re-seed system user (Flyway won't re-run V5 after truncate)
INSERT INTO users (id, email, password_hash, name, role, created_at, updated_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'system@ascend.internal',
    '',
    'System',
    'ADMIN',
    NOW(),
    NOW()
) ON CONFLICT (id) DO NOTHING;
