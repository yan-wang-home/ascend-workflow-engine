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
