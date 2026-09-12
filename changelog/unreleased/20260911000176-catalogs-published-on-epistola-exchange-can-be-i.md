---
type: feat
scopes: [exchange, catalog]
audience: user
title: Catalogs published on Epistola Exchange can be installed.
---

An Exchange release is a catalog archive, so installing one is the ZIP import that already existed, in the mode that treats a catalog as a mirror — stale resources pruned, and the whole thing abandoned without advancing anything if a single resource fails. What is new is everything before that: choosing the release, refusing a withdrawn one even though Exchange keeps returning it to the installation that published it, verifying the archive against the digest published beside it, and refusing an oversized one from its advertised size rather than after the transfer. A catalog is addressed within a tenant by the slug in its own manifest, so `acme/invoices` and `globex/invoices` both want the same local catalog and both are subscribed — the existing type-flip guard cannot see that, and the second install would have silently overwritten the first. It is now refused, naming what holds the ID. Installing is Alpha, off by default, and needs its own `catalog-installing` feature rather than sharing publishing's: the directions are different conversations, and switching off publishing must not stop upgrade checks for catalogs already installed.
