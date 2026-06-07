ALTER TABLE workflow_instances DROP CONSTRAINT workflow_instances_status_check;
ALTER TABLE workflow_instances ADD CONSTRAINT workflow_instances_status_check
    CHECK (status IN ('PENDING', 'CHANGES_REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED'));
