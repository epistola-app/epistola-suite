-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- The authored catalogs of a 1.x installation, queued for the two things 2.0 needs of them and SQL
-- cannot do: which catalogs a catalog references is read from its content by application code, and a
-- release is cut by it.
--
-- - Declarations: a 1.x catalog references other catalogs (the system catalog's fonts and
--   attributes, a shared theme) without declaring them, and 2.0 refuses to release it until it does
--   (contract ADR 0003).
-- - A first release: no 1.x release kept its content, so nothing could render. Each catalog gets one,
--   cut from what was last published, numbered as its draft, and deployed nowhere.
--
-- CatalogUpgradeRunner does both and removes the catalog from the queue; a catalog it cannot do yet
-- (a dependency not released) stays, with the reason. A catalog created under 2.0 is never queued.
CREATE TABLE catalog_upgrade_queue (
    tenant_key TENANT_KEY NOT NULL,
    catalog_id UUID       NOT NULL,
    last_error TEXT,
    PRIMARY KEY (tenant_key, catalog_id),
    FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs (tenant_key, catalog_id) ON DELETE CASCADE
);

COMMENT ON TABLE catalog_upgrade_queue IS
    'Authored catalogs from before 2.0 still to be given their declarations and first release; emptied by CatalogUpgradeRunner.';
COMMENT ON COLUMN catalog_upgrade_queue.last_error IS
    'Why the last attempt did not finish; the runner tries again.';

INSERT INTO catalog_upgrade_queue (tenant_key, catalog_id)
SELECT tenant_key, catalog_id FROM catalogs WHERE type = 'AUTHORED';
