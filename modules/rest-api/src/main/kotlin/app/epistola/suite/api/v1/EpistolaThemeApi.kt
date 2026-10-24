// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.ThemesApi
import app.epistola.api.model.CreateThemeRequest
import app.epistola.api.model.ThemeDto
import app.epistola.api.model.ThemeListResponse
import app.epistola.api.model.UpdateThemeRequest
import app.epistola.suite.api.v1.shared.ETags
import app.epistola.suite.api.v1.shared.ListSorting
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.catalogId
import app.epistola.suite.api.v1.shared.catalogVersionAt
import app.epistola.suite.api.v1.shared.created
import app.epistola.suite.api.v1.shared.draftAt
import app.epistola.suite.api.v1.shared.okWithETag
import app.epistola.suite.api.v1.shared.readRef
import app.epistola.suite.api.v1.shared.ref
import app.epistola.suite.api.v1.shared.toDomainPresets
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.themes.ThemeNotFoundException
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.DeleteTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import app.epistola.suite.themes.queries.ListThemes
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class EpistolaThemeApi : ThemesApi {

    override fun listThemes(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        q: String?,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<ThemeListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        ListSorting.rejectUnsupportedSort(sort, direction)
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        // A draft or a release, read the same way (ADR 0033).
        val items = ListThemes(tenantId = TenantId(at.catalog.tenantKey), searchTerm = q, catalog = at.catalogId, catalogVersion = at.readRef).query().map { it.toDto() }
        val slice = Pagination.paginate(items, page, size)
        return ResponseEntity.ok(ThemeListResponse(items = slice.items, page = slice.page))
    }

    override fun createTheme(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        createThemeRequest: CreateThemeRequest,
    ): ResponseEntity<ThemeDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val theme = CreateTheme(
            id = ThemeId(ThemeKey.of(createThemeRequest.key), draft.catalogId),
            name = createThemeRequest.name,
            description = createThemeRequest.description,
            documentStyles = createThemeRequest.documentStyles ?: emptyMap(),
            pageSettings = createThemeRequest.pageSettings,
            blockStylePresets = createThemeRequest.blockStylePresets.toDomainPresets(),
            spacingUnit = createThemeRequest.spacingUnit?.toFloat(),
            catalogVersion = draft.ref,
        ).execute()

        return theme.toDto().let { created(it.key, it) }
    }

    override fun getTheme(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        themeKey: String,
    ): ResponseEntity<ThemeDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        val key = ThemeKey.of(themeKey)
        val theme = GetTheme(id = ThemeId(key, at.catalogId), catalogVersion = at.readRef).query() ?: throw ThemeNotFoundException(key)
        return okWithETag(theme.revision, theme.toDto())
    }

    override fun updateTheme(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        themeKey: String,
        updateThemeRequest: UpdateThemeRequest,
        ifMatch: String?,
    ): ResponseEntity<ThemeDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val key = ThemeKey.of(themeKey)
        val theme = UpdateTheme(
            id = ThemeId(key, draft.catalogId),
            name = updateThemeRequest.name,
            description = updateThemeRequest.description,
            documentStyles = updateThemeRequest.documentStyles,
            pageSettings = updateThemeRequest.pageSettings,
            blockStylePresets = updateThemeRequest.blockStylePresets.toDomainPresets(),
            spacingUnit = updateThemeRequest.spacingUnit?.toFloat(),
            catalogVersion = draft.ref,
            expectedRevision = ETags.expected(ifMatch),
        ).execute() ?: throw ThemeNotFoundException(key)

        return okWithETag(theme.revision, theme.toDto())
    }

    override fun deleteTheme(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        themeKey: String,
    ): ResponseEntity<Unit> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val key = ThemeKey.of(themeKey)
        if (!DeleteTheme(id = ThemeId(key, draft.catalogId), catalogVersion = draft.ref).execute()) throw ThemeNotFoundException(key)
        return ResponseEntity.noContent().build()
    }
}
