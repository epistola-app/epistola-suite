---
type: feat
scopes: [generation]
audience: user
title: Rendering defaults V8 collapse nested block margins as CSS does.
---

A theme's block baseline (`defaultBlockStyles`) used to be added once per level where blocks nest:
two cards with one paragraph each sat further apart than two loose paragraphs. From rendering
defaults V8, a container's bottom margin and its last child's (and its top margin and its first
child's) collapse into the larger of the two, as on the web. Padding or a border on that side, or a
height, keeps them apart; siblings keep their own spacing. The editor canvas shows the same.

Drafts render with V8 now. A release cut with V7 or earlier renders as it did; releasing a draft
whose base was cut with V7 is a change the release dialog names ("Rendering defaults V7 → V8").
