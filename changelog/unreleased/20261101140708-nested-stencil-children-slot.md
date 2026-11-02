---
type: fix
scopes: [stencils, api]
title: A stencil nesting another written without its children slot no longer breaks the templates using it.
---

A stencil instance saved through the REST API or MCP without its `children` slot, which the editor always writes, was accepted. Inside a template that was harmless, but inside a stencil it made previewing, marking ready and releasing every template using that stencil fail with an internal error. Such an instance now expands like any other.
