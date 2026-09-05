-- Theme relocation
--
-- A theme's address is still its primary key, so moving one updates
-- (catalog_key, id) in place and the two relational references follow by
-- database rule:
--
--   document_templates.theme_catalog_key/theme_key  -- a template's own theme
--   tenants.default_theme_catalog_key/…_key         -- the tenant-wide default
--
-- ON DELETE is preserved exactly. The template reference keeps its column-list
-- SET NULL: deleting a theme clears the template's binding and falls the
-- template back to the tenant default, rather than deleting the template.
--
-- References inside content (`themeRef`) are not covered here — those are
-- rewritten by the move command for drafts and resolved through the alias for
-- published versions, like stencil references.

ALTER TABLE document_templates
    DROP CONSTRAINT document_templates_tenant_key_theme_catalog_key_theme_key_fkey,
    ADD CONSTRAINT document_templates_tenant_key_theme_catalog_key_theme_key_fkey
        FOREIGN KEY (tenant_key, theme_catalog_key, theme_key)
        REFERENCES themes(tenant_key, catalog_key, id)
        ON UPDATE CASCADE
        ON DELETE SET NULL (theme_catalog_key, theme_key);

ALTER TABLE tenants
    DROP CONSTRAINT fk_tenants_default_theme,
    ADD CONSTRAINT fk_tenants_default_theme
        FOREIGN KEY (id, default_theme_catalog_key, default_theme_key)
        REFERENCES themes(tenant_key, catalog_key, id)
        ON UPDATE CASCADE;
