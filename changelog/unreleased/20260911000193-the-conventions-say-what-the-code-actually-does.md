---
type: docs
scopes: [agents]
audience: dev
title: The conventions say what the code actually does again.
---

`CLAUDE.md` had drifted over nine months of additions: it named a Spring Boot and Kotlin version two releases old, drew a source tree missing thirteen of the twenty-one Gradle projects and containing three directories that do not exist, placed the REST controllers in core, called quality checks commercial, described a mediator idiom (`MediatorContext.send`) that was never real while omitting the `Command.execute()` one used at hundreds of call sites, and told readers that folding migrations back is a sanctioned move — which the database-stability rule four sections earlier forbids. Several commands it gave failed when run as written. Those are corrected, the duplicated and conflicting test-and-format cadences are reduced to one each, and `AGENTS.md` now defers to it instead of restating a stricter rule of its own. The commit-type list defers to the commitlint config rather than copying a third variant, and the ban on mentioning AI in commit messages is lifted: attribution trailers may stay, while the subject and body keep describing the change rather than the tooling.
