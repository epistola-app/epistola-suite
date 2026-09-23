-- backup-restore-compatibility: backward=true forward=true
-- reason: Adds a nullable column recording what a release contained. A backup from either side
-- restores; an older reader ignores it, and a release written without it reads as "no baseline".
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Record which resources a release contained, and at which content digest.
--
-- An authored catalog can say *whether* it has unreleased changes -- the working copy's content
-- fingerprint against the released one -- but not *what* changed, because a release stores one
-- fingerprint over the whole catalog. Releasing therefore means trusting that everything which
-- accumulated since the last release is meant to ship.
--
-- This is the same baseline a SUBSCRIBED catalog already keeps in
-- `catalogs.installed_resource_fingerprints`, for the authored direction: "type/slug" -> SHA-256 of
-- that resource's canonical content, from the same canonicaliser that computes the catalog
-- fingerprint, so a per-resource difference is exactly a catalog fingerprint difference localised
-- to one resource.
--
-- Nullable, and left NULL for releases cut before this column existed: their per-resource digests
-- were never computed and cannot be recovered from `manifest_snapshot`, which holds the manifest
-- entries and not the resource payloads. A reader that finds NULL says it has no baseline rather
-- than reporting every resource as new.
ALTER TABLE catalog_releases
    ADD COLUMN resource_fingerprints JSONB;

COMMENT ON COLUMN catalog_releases.resource_fingerprints IS
    'Per-resource canonical content digests of this release ("type/slug" -> SHA-256 hex), the authored counterpart of catalogs.installed_resource_fingerprints. NULL for releases cut before the column existed.';
