CREATE TABLE changelog_acknowledgments (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    version VARCHAR(20) NOT NULL,
    acknowledged_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE changelog_acknowledgments IS 'Tracks which changelog version each user has last dismissed';
COMMENT ON COLUMN changelog_acknowledgments.user_id IS 'User who acknowledged the changelog';
COMMENT ON COLUMN changelog_acknowledgments.version IS 'App version that was acknowledged (semver)';
COMMENT ON COLUMN changelog_acknowledgments.acknowledged_at IS 'When the acknowledgment occurred';
