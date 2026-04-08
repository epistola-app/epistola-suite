CREATE DOMAIN FEATURE_KEY AS VARCHAR(50)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

CREATE TABLE feature_toggles (
    tenant_key  TENANT_KEY  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    feature_key FEATURE_KEY NOT NULL,
    enabled     BOOLEAN     NOT NULL,
    PRIMARY KEY (tenant_key, feature_key)
);
