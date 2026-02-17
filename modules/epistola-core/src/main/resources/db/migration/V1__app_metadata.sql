-- Simple key-value metadata table for application-level settings
CREATE TABLE app_metadata (
    key VARCHAR(100) PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_app_metadata_key ON app_metadata(key);

-- Initialize demo version (0.0.0 will trigger first demo creation)
INSERT INTO app_metadata (key, value) VALUES ('demo_version', '0.0.0');

COMMENT ON TABLE app_metadata IS 'Key-value store for application-level settings and internal state';
COMMENT ON COLUMN app_metadata.key IS 'Setting identifier (e.g., demo_version)';
COMMENT ON COLUMN app_metadata.value IS 'Setting value as text';
COMMENT ON COLUMN app_metadata.updated_at IS 'When this setting was last changed';
