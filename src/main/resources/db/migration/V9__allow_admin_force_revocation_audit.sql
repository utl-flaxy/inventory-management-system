-- B3 only: retain existing users, authentication generations and audit history.
ALTER TABLE auth_revocation_audit
    DROP CONSTRAINT ck_auth_revocation_action,
    ADD CONSTRAINT ck_auth_revocation_action CHECK (action IN ('LOGOUT_ALL', 'PASSWORD_CHANGE', 'FORCE_REVOKE'));
