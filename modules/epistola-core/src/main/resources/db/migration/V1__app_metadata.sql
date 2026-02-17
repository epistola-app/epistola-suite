-- Simple key-value metadata table for application-level settings
CREATE TABLE app_metadata (
    key VARCHAR(100) PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_app_metadata_key ON app_metadata(key);

-- Initialize demo version (0.0.0 will trigger first demo creation)
INSERT INTO app_metadata (key, value) VALUES ('demo_version', '0.0.0');
