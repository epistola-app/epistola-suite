---
type: feat
scopes: [attributes, catalog, storage]
title: Variant attributes are stored as JSON, bind code lists by name, and read a release like a draft.
---

A variant attribute is now stored the way a release keeps it, and its code list binding is what a release says: the list's catalog by the name the attribute's catalog uses for it, and the list's key. Moving or renaming a code list rewrites the bindings of the attributes that use it, and deleting one that is still bound is refused as before. An attribute bound to a code list of another catalog now validates and offers the entries of the version that catalog declares: when it uses a release of that catalog, an entry added to the list since is not a valid value until the catalog moves to a release that has it. The REST and MCP attribute reads take a release number. The upgrade to 2.0 converts every attribute in the migration step; the `variant_attribute_definitions` table is gone.
