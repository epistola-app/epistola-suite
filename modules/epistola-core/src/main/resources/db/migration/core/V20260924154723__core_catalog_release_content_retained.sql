-- backup-restore-compatibility: backward=true forward=true
-- reason: Adds a flag that is false for every row a backup from either side carries, and which the
-- release command sets from then on. Nothing reads it for a release that predates it.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Say whether a release kept its content, instead of inferring it.
--
-- Everything so far read "this release has no `release_entries` rows" as "this release predates
-- content retention". That is two different things wearing one answer: a catalog with no resources
-- can be released, and its release legitimately contains nothing. Asked whether such a release kept
-- its content, the inference says no -- so it cannot be exported as released, and publishing it to
-- Exchange is refused with a message telling the author to release changes they have not made.
--
-- A release now records the fact rather than leaving it to be deduced. FALSE for every existing
-- row, which is correct: those releases retained nothing and cannot be rebuilt.
ALTER TABLE catalog_releases
    ADD COLUMN content_retained BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN catalog_releases.content_retained IS
    'Whether this release stored the content it contained, and so can be rebuilt and exported as released. FALSE for releases cut before V20260923201010, which retained nothing. Not the same as having no release_entries: a catalog with no resources retains an empty release.';
