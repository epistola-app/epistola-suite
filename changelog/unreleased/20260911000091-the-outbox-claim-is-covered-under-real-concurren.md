---
type: test
scopes: [exchange]
audience: dev
title: The outbox claim is covered under real concurrency.
---

`claimDue` uses `FOR UPDATE SKIP LOCKED` and an expiring lease so two nodes can never submit the same release twice, and nothing tested it. Racing two threads and hoping they collide turned out to prove nothing — written that way the test passes with no row locking at all — so one node now claims inside a transaction the test holds open and the other claims while its locks are held. Both the step-over and the lease takeover fail if their guarantee is removed.
