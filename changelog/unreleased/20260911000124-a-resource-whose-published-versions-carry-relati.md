---
type: feat
scopes: [catalogs]
audience: user
title: A resource whose published versions carry relative references can move.
---

Content published before references were qualified on write stored a same-catalog dependency relatively, and the `immutable-relative-reference` blocker then made its owner permanently immovable — publishing again does not retire the old version. Relocation now pins such references to the catalog they resolve against today, published versions included: the bytes change, the meaning does not, and the released-catalog rule keeps released content untouched. Templates get the same treatment; their own versions were not examined at all before, so a draft reopened after a move would have been qualified against the wrong catalog. A batch that moves only templates now reads only those templates' versions instead of the whole tenant.
