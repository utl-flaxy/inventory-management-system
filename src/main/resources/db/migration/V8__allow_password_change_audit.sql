-- Add only the reviewed B2 action. Existing users, generations and audit rows are preserved.
ALTER TABLE auth_revocation_audit
    DROP CONSTRAINT ck_auth_revocation_action,
    ADD CONSTRAINT ck_auth_revocation_action CHECK (action IN ('LOGOUT_ALL', 'PASSWORD_CHANGE'));
