-- Add surrogate PK to group_members so Spring Data R2DBC can save/find rows.
-- The (group_id, user_id) uniqueness is preserved by the unique constraint.
ALTER TABLE group_members ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE group_members DROP CONSTRAINT group_members_pkey;
ALTER TABLE group_members ADD CONSTRAINT group_members_group_user_unique UNIQUE (group_id, user_id);
ALTER TABLE group_members ADD PRIMARY KEY (id);
