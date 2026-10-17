-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- A catalog from 1.x references other catalogs (the system catalog's fonts and attributes, a shared
-- theme) without declaring them, since 1.x had no declarations, and 2.0 refuses to release it until
-- it does (contract ADR 0003). The authored catalogs that exist now are queued here, and
-- CatalogDependencyBackfillRunner declares what each references and removes it from the queue. A
-- catalog created under 2.0 is never queued, so what its author leaves undeclared stays undeclared.
--
-- The declaring is not done here: which catalogs a catalog references is read from its content by
-- application code (the same as when a resource moves to another catalog), not by SQL.
CREATE TABLE catalog_dependency_backfill (
    tenant_key TENANT_KEY NOT NULL,
    catalog_id UUID       NOT NULL,
    PRIMARY KEY (tenant_key, catalog_id),
    FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs (tenant_key, catalog_id) ON DELETE CASCADE
);

COMMENT ON TABLE catalog_dependency_backfill IS
    'Authored catalogs from before 2.0 whose references still have to be declared as dependencies; emptied by CatalogDependencyBackfillRunner.';

INSERT INTO catalog_dependency_backfill (tenant_key, catalog_id)
SELECT tenant_key, catalog_id FROM catalogs WHERE type = 'AUTHORED';
