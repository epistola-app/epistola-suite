---
type: feat
scopes: [catalog]
title: Contract 2.0.0-alpha.3 — producer metadata belongs to the resource, not the variant, and counts in no fingerprint.
---

The suite builds against contract `2.0.0-alpha.3`. Producer metadata (`metadata`, by reverse-DNS
namespace) is now a property of every catalog resource, and no fingerprint covers it: it is what a
producer says about the content, not the content. A template variant no longer has metadata of its
own. What a received catalog said of one variant is kept on its template, under
`{"<namespace>": {"variants": {"<variant>": …}}}`, exactly as the contract folds an early archive;
a migration moves every draft variant's metadata there, and a release retained earlier exports it the
same way.
