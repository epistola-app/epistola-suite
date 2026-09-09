<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: AGPL-3.0-only
-->

# Catalog test fixture

A **frozen copy** of the demo catalog, taken when demo mode moved into `apps/epistola-demo`.

Catalog tests across several projects — export/import round-trips, upgrade paths, repeated
deployment, resource deserialization, relative-URL resolution, the catalog UI handlers, the REST
catalog API — need a broad, realistic catalog to assert against, and they used to read the bundled
demo one out of `epistola-core`'s main resources. That coupled them to content that changes for demo
reasons: [CLAUDE.md](../../../../../../../CLAUDE.md) requires every new feature to be demonstrated in
the demo catalog, so a demo edit could break tests with nothing to do with the demo.

It lives here, in `modules/testing`, because more than one project needs it and this module is
already the shared test infrastructure. `modules/testing` is only ever a `testImplementation`
dependency, so nothing here reaches a production artifact.

**Do not sync it with the demo catalog.** It is a fixture: it has to be a valid, varied catalog, not
a current one. Change it only when a test needs something it does not yet contain. Its
`release.fingerprint` is therefore not maintained, and no test asserts it.

The shipped demo catalog lives at `apps/epistola-demo/src/main/resources/epistola/catalogs/demo/`,
and `DemoCatalogFingerprintTest` in that app asserts its fingerprint. The `system` catalog stays in
`modules/epistola-core/src/main/resources/` — every tenant gets it, so it is genuinely core's.
