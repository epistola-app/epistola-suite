---
type: feat
scopes: [catalog]
audience: user
maturity: alpha
title: Moving or renaming a catalog resource works while its catalog has several drafts.
---

Relocation (alpha, behind `resource-relocation`) changes one draft per catalog: the draft you are working in for the catalog a resource moves out of, and the next version of every other catalog involved. It used to be refused while a catalog had parallel work; now the catalog's other drafts are left alone, keep their copy at the old address, and are named in the preview. The resource keeps its history (generation history, quality findings) at its new address. A catalog that now uses a resource moved into another catalog depends on that catalog's next version, since no release of it holds the resource yet. Merging a fix released from a draft that was left alone recognises what moved: a fix to a template that names a moved theme follows the move, and a fix to the moved theme itself says where the theme went.
