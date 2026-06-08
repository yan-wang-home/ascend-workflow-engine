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

-- Seed demo users (password: password123). Registration API always creates REQUESTER;
-- these seeds ensure each user has the correct role for the demo without needing a
-- role-promotion step.
INSERT INTO users (id, email, password_hash, name, role, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000002', 'admin@ascend.com',
     '$2b$10$UxrU//oAw1XLP21VmLKsPuPlLGNTJIRpUmR0kqLpkeDv.haA62Wl2',
     'Admin User', 'ADMIN', NOW(), NOW()),
    ('00000000-0000-0000-0000-000000000003', 'manager@ascend.com',
     '$2b$10$oKV795UR2MhodEvR5DKhieMtERZjnbU9.b1EYviigc7Re8uxqJ1SS',
     'Alice Manager', 'APPROVER', NOW(), NOW()),
    ('00000000-0000-0000-0000-000000000004', 'finance@ascend.com',
     '$2b$10$R9JIDhibXZTjLPjato/4beLB4QDurpYnBZ4taI3L8y9CMqvoM6Z6q',
     'Bob Finance', 'APPROVER', NOW(), NOW()),
    ('00000000-0000-0000-0000-000000000005', 'vp@ascend.com',
     '$2b$10$P1jLqN64D5LpSjmXjKzZ9ulRUGqNGrUk1uK4UVxCAzU3Kh3SoG6A6',
     'Carol VP', 'APPROVER', NOW(), NOW()),
    ('00000000-0000-0000-0000-000000000006', 'requester@ascend.com',
     '$2b$10$BeZLnMWYFimvUVbeAXbSyuQ8En9b44W9dPPagoNG3kfz4zIUPvTMW',
     'Jane Requester', 'REQUESTER', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
