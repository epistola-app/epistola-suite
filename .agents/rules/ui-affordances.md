---
paths:
  - "**/templates/**/*.html"
  - "**/src/main/kotlin/**/handlers/**/*.kt"
---

# A control appears only when it can be used

This rule spans template and handler on purpose: the handler owns the predicate, the template gates
on it. Splitting that is how two screens on the same domain state come to disagree.

- **Permission is necessary, never sufficient.** Offer a control when the principal _may_ act **and**
  the action has somewhere to land. A gate on the role alone produces a screen that takes the
  instruction and then cannot carry it out.
- **The read model owns the conjunction; the template gates on one name.** Name the combined
  predicate on the query result and gate on that single name.
  `CatalogPublicationState.hasPublishableDestination`
  ([`GetCatalogPublicationState.kt`](../../modules/epistola-core/src/main/kotlin/app/epistola/suite/exchange/GetCatalogPublicationState.kt))
  is the shape: one property, one KDoc explaining the dead end it prevents. A template that ANDs two
  raw flags instead is a rule with no single home, and the second screen to be written will get it
  wrong.
- **A withheld control states why, and where to fix it.** Hiding silently is the defect, not the
  cure — the reader cannot tell a missing permission from a missing precondition from a bug.
  Distinct reasons get distinct sentences; `catalogs/list.html` carries three (not connected / no
  namespace granted / bound to a revoked namespace) and is the reference for tone and length.
- **A `required` `<select>` whose options come from a collection sits inside a guard on that
  collection.** With no options the browser refuses to submit and says only "please select an item
  in the list", which names nothing the reader can act on. The guard belongs on the select or an
  ancestor, in the same file.
- **An edit control for absent content shows the empty state and the route that creates it** — never
  a picker with nothing in it. `catalogs/metadata.html` is the shape: one sentence saying what is
  missing, and a link to where it is added.

The same reasoning covers a search box on a list with nothing in it, and a second copy of a primary
action in an empty state that already has one in the page header. Both are controls offered on the
strength of "this screen exists" rather than "this will do something".

One caveat, because getting it wrong strands people worse than the original bug: a control that
filters or searches must **not** disappear once a filter has emptied the list, or the reader cannot
clear it. The condition is _nothing to act on **and** no filter active_.

## Verify

```bash
./gradlew :modules:guards:test
```
