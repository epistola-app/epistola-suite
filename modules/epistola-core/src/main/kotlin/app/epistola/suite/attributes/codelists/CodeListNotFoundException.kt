package app.epistola.suite.attributes.codelists

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantKey

class CodeListNotFoundException(
    val tenantId: TenantKey,
    val catalogId: CatalogKey,
    val codeListId: CodeListKey,
) : RuntimeException("Code list ${codeListId.value} not found in catalog ${catalogId.value} for tenant ${tenantId.value}")
