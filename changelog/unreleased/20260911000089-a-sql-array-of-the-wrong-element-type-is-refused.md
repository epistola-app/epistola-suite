---
type: fix
scopes: [config]
audience: dev
title: A SQL array of the wrong element type is refused where it can be understood.
---

The generic array mapper cannot see the declared type, so it passed the driver's objects straight through — a `uuid[]` read into a `List<String>` became a ClassCastException somewhere else entirely. It now fails at the mapping, naming the column and the element type it found.
