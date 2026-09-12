---
type: perf
scopes: [db]
audience: dev
title: The identity migration no longer rewrites the two largest tables.
---

Generation history is filled forward, as it was originally, rather than backfilled: backfilling meant a full row rewrite of every partition of `documents` and `document_generation_requests` inside one transaction, holding `ACCESS EXCLUSIVE` on both throughout, to buy correctness only for rows written before the upgrade — which retention removes within one window anyway. The redundant `count(*)` over both tables (whose result was discarded) goes with it, and the two partitioned indexes are created `ON ONLY` with per-partition children attached, so nothing scans generation history at upgrade time. The foreign-key drops are now ordered, so every run takes its locks in the same sequence instead of whatever `pg_constraint` returns.
