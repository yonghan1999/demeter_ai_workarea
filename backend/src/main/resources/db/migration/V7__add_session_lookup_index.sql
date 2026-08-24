CREATE INDEX idx_auth_sessions_user_active
    ON auth_sessions (user_id, revoked_at, expires_at, created_at);
