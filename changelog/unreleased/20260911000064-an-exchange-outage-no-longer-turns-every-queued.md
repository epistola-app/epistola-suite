---
type: fix
scopes: [exchange]
audience: user
title: An Exchange outage no longer turns every queued release into a failure.
---

Being unable to reach Exchange was counted against each publication's retry budget, so about three quarters of an hour of downtime exhausted it and left every queued release terminally failed, to be retried by hand one at a time. An unreachable Exchange now defers on the slow cadence with the reason recorded, spends no retries, keeps its archive and resumes on its own when Exchange returns; the connection is not marked broken either. Error responses still count, which is what the budget is for.
