-- Composite indexes to speed up the four inbox UNION queries in ApprovalService.getInbox()
CREATE INDEX IF NOT EXISTS idx_instance_steps_status_order
    ON instance_steps (status, step_order);

CREATE INDEX IF NOT EXISTS idx_delegations_delegate_dates
    ON delegations (delegate_id, starts_at, ends_at);

CREATE INDEX IF NOT EXISTS idx_group_members_user_group
    ON group_members (user_id, group_id);
