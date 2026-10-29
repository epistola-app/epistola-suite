---
type: feat
scopes: [rest-api, templates]
breaking: true
title: A template can be created over the REST API with its data contract.
---

`POST …/templates` takes an optional `dataModel` and `dataExamples`, so a template need not exist
without its contract. They are held to what an update of the contract is held to: at least one
example, each satisfying the data model, or the template is refused with 422 and nothing is created.
Following the 2.0 contract, templates no longer carry a `schema` member: the suite never stored it
and always returned null, and the data contract is `dataModel` alone.
