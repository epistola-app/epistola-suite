-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Quality results by what was checked (ADR 0044). A source's findings for a variant are stored under
-- the INPUT KEY of what it read: the variant's content, its template's content, what those refer to as
-- the catalog version resolves it, and the tenant's language. Every catalog version, a draft or a
-- release, shows for each variant the check whose key equals the variant's key in that version; a
-- version where the variant's key has no check yet shows the newest one, as outdated. So a draft cut
-- from a release shows the release's findings, a release keeps its own, and an edit anywhere the key
-- covers makes a finding outdated instead of leaving it to claim to be current.
--
-- A finding is no longer resolved by storing it so: a problem the current check does not report is
-- simply not shown. Only a note a person raised (source 'manual') is resolved, by a person; it belongs
-- to its template, not to a check, and shows in every version. Comments belong to the problem, as
-- ignores already did: they hold across versions and checks.

-- 1. The input key --------------------------------------------------------------------------------
-- What a check of a variant read, in one version, as one hash; NULL when the version does not hold the
-- template. Each part is named, so two parts never run together:
--   t:  the template's content (its data model, examples, theme, variants and their attributes);
--   v:  the variant's content ('none' for a variant without content of its own);
--   r:  each resource either content refers to, by the content the version resolves it to: in its
--       own catalog, the row of this version; in another, the row of the version the alias declares;
--       'none' where nothing answers, which is itself something a check reports;
--   d:  the catalog default theme of this version, by its content;
--   l:  the tenant's language, which a variant without its own falls back to.
-- Not covered, and so not able to make a finding outdated: the installation's default language, a
-- provider's own configuration (a LanguageTool upgrade, a built-in rule changed by a release of this
-- suite), and what a referenced resource refers to in turn. A provider's configuration is the text
-- result cache's key (quality_text_results), so a check after such a change is still right.
CREATE FUNCTION quality_input_key(p_tenant TEXT, p_version_id UUID, p_template_id UUID, p_variant_key TEXT)
    RETURNS VARCHAR(64)
    LANGUAGE sql
    STABLE
AS $$
WITH template AS (
    SELECT r.catalog_id, r.content_hash
    FROM release_resources r
    WHERE r.tenant_key = p_tenant AND r.version_id = p_version_id AND r.resource_id = p_template_id
      AND r.resource_type = 'template' AND r.parent_id IS NULL
),
variant AS (
    SELECT r.content_hash
    FROM release_resources r
    WHERE r.tenant_key = p_tenant AND r.version_id = p_version_id AND r.parent_id = p_template_id
      AND r.resource_type = 'variant' AND r.resource_key = p_variant_key
),
contents AS (
    SELECT content_hash FROM template
    UNION SELECT content_hash FROM variant
),
refs AS (
    SELECT DISTINCT x.alias, x.resource_type, x.resource_key,
           resolve_catalog_alias(p_tenant, t.catalog_id, p_version_id, NULL, x.alias) AS catalog_id
    FROM contents c
    JOIN resource_references x ON x.tenant_key = p_tenant AND x.content_hash = c.content_hash
    CROSS JOIN template t
),
resolved AS (
    SELECT refs.*,
           CASE
               WHEN refs.catalog_id = t.catalog_id THEN p_version_id
               ELSE (SELECT COALESCE(d.target_version_id, pinned.version_id)
                     FROM catalog_version_dependencies d
                     LEFT JOIN catalog_versions pinned
                            ON pinned.tenant_key = d.tenant_key AND pinned.state = 'released' AND pinned.version = d.release_version
                           AND pinned.catalog_id = epistola_catalog_id(d.tenant_key, d.namespace, d.dependency_key)
                     WHERE d.tenant_key = p_tenant AND d.version_id = p_version_id AND d.alias = refs.alias)
           END AS in_version
    FROM refs CROSS JOIN template t
),
parts AS (
    SELECT 't:' || t.content_hash AS part FROM template t
    UNION ALL
    SELECT 'v:' || COALESCE((SELECT content_hash FROM variant), 'none')
    UNION ALL
    SELECT 'r:' || COALESCE(x.alias, '') || '/' || x.resource_type || '/' || x.resource_key || '=' ||
           COALESCE((SELECT r.content_hash FROM release_resources r
                     WHERE r.tenant_key = p_tenant AND r.version_id = x.in_version AND r.catalog_id = x.catalog_id
                       AND r.resource_type = x.resource_type AND r.resource_key = x.resource_key AND r.parent_id IS NULL), 'none')
    FROM resolved x
    UNION ALL
    SELECT 'd:' || COALESCE((SELECT r.content_hash FROM catalog_versions v
                             JOIN release_resources r ON r.tenant_key = v.tenant_key AND r.version_id = v.version_id
                                  AND r.resource_id = v.default_theme_resource_id AND r.parent_id IS NULL
                             WHERE v.tenant_key = p_tenant AND v.version_id = p_version_id), 'none')
    UNION ALL
    SELECT 'l:' || COALESCE((SELECT default_locale FROM tenants WHERE id = p_tenant), '')
)
SELECT CASE WHEN EXISTS (SELECT 1 FROM template)
            THEN encode(sha256(convert_to(string_agg(part, E'\n' ORDER BY part), 'UTF8')), 'hex') END
FROM parts
$$;

COMMENT ON FUNCTION quality_input_key(TEXT, UUID, UUID, TEXT) IS
    'What a quality check of a variant reads in one catalog version, as a hash (ADR 0044): the template''s and the variant''s content, what they refer to as that version resolves it, the version''s default theme and the tenant''s language. NULL when the version does not hold the template.';

-- 2. A check: one source, one variant, one input key ---------------------------------------------
-- That a source checked what the key names, even when it found nothing: an empty answer is an answer,
-- and the difference between "checked, nothing wrong" and "not checked yet" is exactly this row.
CREATE TABLE quality_checks (
    tenant_key           TENANT_KEY  NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    template_resource_id UUID        NOT NULL,
    variant_key          VARIANT_KEY NOT NULL,
    source_id            VARCHAR(64) NOT NULL,
    input_key            VARCHAR(64) NOT NULL,
    checked_at           TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_key, template_resource_id, variant_key, source_id, input_key),
    -- A check is of its template's identity and goes with it, whichever version held it.
    CONSTRAINT quality_checks_template_fkey FOREIGN KEY (tenant_key, template_resource_id)
        REFERENCES catalog_resources (tenant_key, resource_id) ON DELETE CASCADE
);

COMMENT ON TABLE quality_checks IS
    'That a quality source checked a variant as the input key names it (ADR 0044); its findings hang off it. A version shows the check whose key is the variant''s key there, else the newest, as outdated. Superseded checks are deleted when the same source checks the variant again and no version has their key any more.';

-- 3. Findings: under their check, or a person's note ----------------------------------------------
ALTER TABLE quality_findings ADD COLUMN input_key VARCHAR(64);

-- A comment moves from the finding's row to the problem (5.) before any row goes.
ALTER TABLE quality_finding_comments
    ADD COLUMN template_resource_id UUID,
    ADD COLUMN ignore_scope_urn    VARCHAR(512),
    ADD COLUMN source_id           VARCHAR(64),
    ADD COLUMN rule_id             VARCHAR(128),
    ADD COLUMN finding_fingerprint VARCHAR(128);

UPDATE quality_finding_comments c
SET template_resource_id = f.template_resource_id,
    ignore_scope_urn     = f.ignore_scope_urn,
    source_id            = f.source_id,
    rule_id              = f.rule_id,
    finding_fingerprint  = f.fingerprint
FROM quality_findings f
WHERE f.tenant_key = c.tenant_key AND f.id = c.finding_id;

-- Off the row before any finding goes, so no comment goes with one.
ALTER TABLE quality_finding_comments DROP COLUMN finding_id;

-- The findings of a 1.x check were computed from a 1.x document and are of no version: each open one
-- on a variant joins one check per variant and source under a key no version has, so it shows as
-- outdated until the variant is checked again, which replaces it. A resolved one is gone: what it
-- said is no longer true, and its comments are kept on the problem. A finding a check made about a
-- whole template has no variant to be checked again, and goes too. A person's notes are kept as they
-- are, resolved ones included.
DELETE FROM quality_findings
WHERE source_id <> 'manual' AND (status = 'RESOLVED' OR variant_key IS NULL);

INSERT INTO quality_checks (tenant_key, template_resource_id, variant_key, source_id, input_key, checked_at)
SELECT tenant_key, template_resource_id, variant_key, source_id, 'checked-before-2.0', MAX(last_seen_at)
FROM quality_findings
WHERE source_id <> 'manual'
GROUP BY tenant_key, template_resource_id, variant_key, source_id;

UPDATE quality_findings SET input_key = 'checked-before-2.0' WHERE source_id <> 'manual';

DROP INDEX uq_quality_findings_reconcile;
ALTER TABLE quality_findings
    DROP COLUMN input_fingerprint,
    -- A check's findings go with it: a superseded check takes them along.
    ADD CONSTRAINT quality_findings_check_fkey
        FOREIGN KEY (tenant_key, template_resource_id, variant_key, source_id, input_key)
        REFERENCES quality_checks (tenant_key, template_resource_id, variant_key, source_id, input_key) ON DELETE CASCADE,
    ADD CONSTRAINT quality_findings_check_or_note CHECK ((source_id = 'manual') = (input_key IS NULL)),
    -- Only a person's note is resolved and kept so; a check's finding is shown while its check reports it.
    ADD CONSTRAINT quality_findings_resolved_note CHECK (status = 'OPEN' OR source_id = 'manual');

-- A check reports a problem once: reconciled per check, on the problem's fingerprint.
CREATE UNIQUE INDEX uq_quality_findings_check
    ON quality_findings (tenant_key, template_resource_id, variant_key, source_id, input_key, fingerprint)
    WHERE input_key IS NOT NULL;

COMMENT ON TABLE quality_findings IS
    'Quality findings: what a check reported (input_key set, under its row in quality_checks), or a note a person raised (source ''manual'', input_key NULL), which belongs to its template and shows in every version. A check''s finding is shown by the versions whose input key is its check''s (ADR 0044).';
COMMENT ON COLUMN quality_findings.input_key IS
    'The input key of the check that reported this finding (quality_checks); NULL for a person''s note, which no check computed and no edit makes outdated.';
COMMENT ON COLUMN quality_findings.status IS
    'OPEN, or RESOLVED for a person''s note a person resolved. A check''s finding is always OPEN: one its source no longer reports is not shown, not resolved. IGNORED is derived from a live quality_finding_ignores row.';

-- 4. Which check a version shows ------------------------------------------------------------------
-- Per variant of every catalog version, its input key there; a statement filters it by the version it
-- means (ADR 0049).
CREATE VIEW quality_version_variants AS
SELECT v.tenant_key, v.catalog_id, v.version_id, v.template_resource_id, v.id AS variant_key,
       quality_input_key(v.tenant_key, v.version_id, v.template_resource_id, v.id) AS input_key
FROM version_template_variants v;

COMMENT ON VIEW quality_version_variants IS
    'Per variant of every catalog version, its quality input key there (ADR 0044).';

-- Per such variant and source, the check shown: the one with the variant's key, else the newest, as
-- outdated.
CREATE VIEW quality_version_checks AS
SELECT DISTINCT ON (q.tenant_key, q.version_id, q.template_resource_id, q.variant_key, k.source_id)
       q.tenant_key, q.catalog_id, q.version_id, q.template_resource_id, q.variant_key, k.source_id,
       k.input_key, k.checked_at, (k.input_key = q.input_key) AS current
FROM quality_version_variants q
JOIN quality_checks k
  ON k.tenant_key = q.tenant_key AND k.template_resource_id = q.template_resource_id AND k.variant_key = q.variant_key
ORDER BY q.tenant_key, q.version_id, q.template_resource_id, q.variant_key, k.source_id,
         (k.input_key = q.input_key) DESC, k.checked_at DESC;

COMMENT ON VIEW quality_version_checks IS
    'Per variant of every catalog version and per source, the check shown: the one with the variant''s input key there (current), else the newest (outdated).';

-- 5. Comments belong to the problem --------------------------------------------------------------
-- Keyed as an ignore is: by the template's scope, the source, the rule and the fingerprint. So a
-- discussion holds in every version, and through a check that no longer reports the problem and a
-- later one that does again.
ALTER TABLE quality_finding_comments
    ALTER COLUMN template_resource_id SET NOT NULL,
    ALTER COLUMN ignore_scope_urn SET NOT NULL,
    ALTER COLUMN source_id SET NOT NULL,
    ALTER COLUMN rule_id SET NOT NULL,
    ALTER COLUMN finding_fingerprint SET NOT NULL,
    ADD PRIMARY KEY (tenant_key, id),
    ADD CONSTRAINT quality_finding_comments_template_fkey FOREIGN KEY (tenant_key, template_resource_id)
        REFERENCES catalog_resources (tenant_key, resource_id) ON DELETE CASCADE;

CREATE INDEX idx_quality_finding_comments_problem
    ON quality_finding_comments (tenant_key, ignore_scope_urn, source_id, rule_id, finding_fingerprint);
CREATE INDEX idx_quality_finding_comments_template ON quality_finding_comments (tenant_key, template_resource_id);

COMMENT ON TABLE quality_finding_comments IS
    'Discussion on a problem, keyed as an ignore is (scope, source, rule, fingerprint): it holds in every version and through every check that reports the problem. Goes with its template. Local only.';

-- 6. A variant no version holds any more takes its findings ---------------------------------------
-- Replaces the 2.0 step's trigger, which looked for variants in a template's content: since then a
-- variant is a row of its own. Its checks, and with them their findings, and the notes on it go when
-- the last row of any version holding it goes. A draft dropping a variant a release still holds
-- leaves them: the release shows them.
DROP TRIGGER trg_release_resources_quality_findings_follow_variant ON release_resources;
DROP FUNCTION quality_findings_follow_variant();

CREATE FUNCTION quality_follow_variant() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM release_resources r
        WHERE r.tenant_key = OLD.tenant_key AND r.parent_id = OLD.parent_id AND r.resource_key = OLD.resource_key
    ) THEN
        DELETE FROM quality_checks
        WHERE tenant_key = OLD.tenant_key AND template_resource_id = OLD.parent_id AND variant_key = OLD.resource_key;
        DELETE FROM quality_findings
        WHERE tenant_key = OLD.tenant_key AND template_resource_id = OLD.parent_id AND variant_key = OLD.resource_key;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_release_resources_quality_follow_variant
    AFTER DELETE OR UPDATE OF resource_key ON release_resources
    FOR EACH ROW WHEN (OLD.resource_type = 'variant')
    EXECUTE FUNCTION quality_follow_variant();

-- 7. Superseded checks ----------------------------------------------------------------------------
-- The input keys a variant has in some version now: a check under any other is superseded.
CREATE FUNCTION quality_live_input_keys(p_tenant TEXT, p_template_id UUID, p_variant_key TEXT)
    RETURNS SETOF VARCHAR(64)
    LANGUAGE sql
    STABLE
AS $$
SELECT DISTINCT quality_input_key(p_tenant, r.version_id, p_template_id, p_variant_key)
FROM release_resources r
WHERE r.tenant_key = p_tenant AND r.resource_id = p_template_id AND r.resource_type = 'template' AND r.parent_id IS NULL
$$;

COMMENT ON FUNCTION quality_live_input_keys(TEXT, UUID, TEXT) IS
    'The quality input keys a variant has in the versions that hold its template now (ADR 0044): a check under another key is shown by no version.';
