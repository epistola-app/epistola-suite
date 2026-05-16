CREATE DOMAIN FEATURE_KEY AS VARCHAR(50)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

CREATE TABLE feature_toggles (
    tenant_key  TENANT_KEY  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    feature_key FEATURE_KEY NOT NULL,
    enabled     BOOLEAN     NOT NULL,
    PRIMARY KEY (tenant_key, feature_key)
);

COMMENT ON TABLE feature_toggles IS 'Per-tenant feature flag overrides. Absence of a row means the feature uses its default state.';
COMMENT ON COLUMN feature_toggles.tenant_key IS 'Owning tenant';
COMMENT ON COLUMN feature_toggles.feature_key IS 'Feature identifier slug (matches the application-side feature key)';
COMMENT ON COLUMN feature_toggles.enabled IS 'Whether the feature is enabled for this tenant';
