---
type: docs
scopes: [agents]
audience: dev
title: The review and editor-component skills are current again.
---

`pr-review` still judged changes against the pre-GA rule that APIs may break freely, pointed at a validation seam removed in June, and told reviewers to use an inline `<script>` that the strict CSP rejects — a review skill enforcing three rules the project had already abandoned. It is rewritten and cut from 218 lines to 91: it routes by what the diff touches and spends the review on what the guards cannot see, rather than restating conventions that now live in one place. `editor-component` was checked before being rewritten rather than assumed dead: its Lit patterns were still accurate, but it named a CSS directory that does not exist and never mentioned document blocks — the half of the editor that carries the PR-blocking `examples[]` rule, the PDF renderer and the demo-catalog requirement. It now covers both paths, with `components/qrcode/` as the worked example.
