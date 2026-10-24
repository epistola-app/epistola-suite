-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- A template's URN names its catalog by address (ADR 0034, section 9): `tenant/namespace/key/…`, since
-- two catalogs of a tenant may share a key. Findings and ignores written before name the catalog by
-- key alone (`tenant/key/…`); each gets its template's namespace, so an ignore keeps matching its
-- findings, and two catalogs with one key no longer share one URN.

UPDATE quality_findings f
SET subject_urn = regexp_replace(f.subject_urn, '^(urn:epistola:[a-z-]+:[^/]+/)', '\1' || c.namespace || '/'),
    ignore_scope_urn = regexp_replace(f.ignore_scope_urn, '^(urn:epistola:[a-z-]+:[^/]+/)', '\1' || c.namespace || '/')
FROM catalog_resources r
JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
WHERE r.tenant_key = f.tenant_key AND r.resource_id = f.template_resource_id;

UPDATE quality_finding_ignores i
SET ignore_scope_urn = regexp_replace(i.ignore_scope_urn, '^(urn:epistola:[a-z-]+:[^/]+/)', '\1' || c.namespace || '/')
FROM catalog_resources r
JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
WHERE r.tenant_key = i.tenant_key AND r.resource_id = i.template_resource_id;
