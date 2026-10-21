-- Who released a release: the user, for one cut in this installation (released_by, which was never
-- written before), and the installation that cut it, for every release (received ones say so in
-- release.metadata, app.epistola.suite.releasedByInstallation). Releases recorded before keep NULL:
-- nothing says who released them.
ALTER TABLE catalog_releases ADD COLUMN released_by_installation UUID;

COMMENT ON COLUMN catalog_releases.released_by IS 'users.id of the user who released it in this installation; NULL for a received or system release, or one recorded before 2.0 wrote it. No FK to avoid cross-table ordering coupling.';
COMMENT ON COLUMN catalog_releases.released_by_installation IS 'app_metadata installation id of the installation that cut the release: this one for a release cut here, the releasing one (from release.metadata) for a received release; NULL when unknown.';
