-- Per-tenant override of the application-wide default locale
-- (epistola.i18n.default-locale). NULL = use the app default.
-- Authoritative validation against the `system.bcp-47` code list happens
-- application-side (SetTenantDefaultLocale command); the CHECK below is
-- only a defensive shape guard for the BCP-47 tag format.
ALTER TABLE tenants
    ADD COLUMN default_locale VARCHAR(35);

ALTER TABLE tenants
    ADD CONSTRAINT tenants_default_locale_format
    CHECK (default_locale IS NULL OR default_locale ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$');

COMMENT ON COLUMN tenants.default_locale IS
    'Per-tenant override of epistola.i18n.default-locale. Validated against system.bcp-47 in application code. NULL = use app default.';
