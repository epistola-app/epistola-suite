---
type: feat
scopes: [support]
audience: user
title: Support requests name who wrote them by a person reference, never by name, while tenants are confidential.
---

A support request sent to a support connection carries who submitted it as a **person reference**
(`p-` and 16 hex digits, computed from the user's id under the installation's own secret): the same
for every request of one person, so whoever handles the requests can tell that they come from one
person, never who. A reply written in the installation carries its author's reference too; while the
installation keeps its tenants confidential (the default), it carries the reference alone, where it
used to carry the author's name and email address.
