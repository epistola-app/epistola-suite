-- backup-restore-compatibility: backward=true forward=true
-- reason: Adds a nullable column to generation_results, which tenant backups exclude. A backup from
-- either side carries nothing for it, so restore is unaffected either way.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- A collected generation result names the catalog release it rendered.
--
-- From contract 2.0 a request renders a catalog release, never a template version, and the collect
-- feed reports `releaseVersion` in place of `versionId`. version_id stays for results emitted
-- before the change; release_version is null for those.
ALTER TABLE generation_results ADD COLUMN release_version VARCHAR(50);

COMMENT ON COLUMN generation_results.release_version IS
    'The catalog release the request rendered; null for results of requests accepted before 2.0.';
