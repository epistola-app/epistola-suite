// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.config.NOT_FOUND_ORIGINAL_PATH_ATTRIBUTE
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.nav.NavMenuAggregator
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView

/**
 * Populates common model attributes needed by tenant-scoped MVC views.
 *
 * The shared tenant-view attributes (`tenantName`, `auth`, `isManager`, editor
 * feature flags, footer chrome) come from [CommonViewModel] — the SAME source the
 * HTMX fragment render path uses ([HtmxFragmentModelContributor]) so the two
 * paths can't drift. This interceptor adds, on top, the shell-only nav model
 * (`navGroups`, `activeNavSection`) and the [workspace][Workspace]
 * — the context bar and side nav — when rendering `layout/shell`.
 *
 * The [CommonViewModel] attributes are merged into EVERY view that renders (not
 * just tenant-scoped ones) — `auth` and `isManager` are deliberately always
 * present so templates never need null checks; only `tenantName` and the footer
 * chrome are tenant-gated inside [CommonViewModel] itself. The extra nav model
 * below is the part that requires a `tenantId`, and only on `layout/shell`.
 */
@Component
class ShellModelInterceptor(
    private val commonViewModel: CommonViewModel,
    private val navMenuAggregator: NavMenuAggregator,
    private val workspaceBuilder: WorkspaceBuilder,
) : HandlerInterceptor {

    override fun postHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        modelAndView: ModelAndView?,
    ) {
        if (modelAndView == null) return
        val viewName = modelAndView.viewName ?: return

        // Shared tenant-view attributes (one source of truth). Never override a
        // key a handler already set.
        commonViewModel.attributes(modelAndView.model).forEach { (key, value) ->
            if (modelAndView.model[key] == null) modelAndView.addObject(key, value)
        }

        // The module-contributed nav menu is shell-only; contributors resolve their own
        // visibility, so the host owns no feature flags for it.
        if (viewName != "layout/shell") return
        val tenantId = modelAndView.model["tenantId"]?.toString()
        if (tenantId == null) {
            modelAndView.addObject("navGroups", emptyList<Any>())
            modelAndView.addObject("activeNavSection", "")
            return
        }
        val auth = modelAndView.model["auth"] as AuthContext
        val context = UiRequestContext(TenantKey.of(tenantId)) { auth.has(it) }
        val requestPath =
            request.getAttribute(NOT_FOUND_ORIGINAL_PATH_ATTRIBUTE) as? String ?: request.requestURI
        val nav = navMenuAggregator.build(context, requestPath)
        modelAndView.addObject("navGroups", nav.groups)
        modelAndView.addObject("activeNavSection", nav.activeNavSection)
        val tenantName = modelAndView.model["tenantName"]?.toString() ?: tenantId
        val workspace = workspaceBuilder.build(TenantKey.of(tenantId), tenantName, auth, modelAndView.model, request, requestPath, nav)
        modelAndView.addObject("workspace", workspace)
        boostInsideWorkspace(request, response, modelAndView, workspace)
    }

    /**
     * A boosted navigation from one workspace page to another replaces only the page
     * (docs/workspace-shell.md): the response is `layout/boosted`, retargeted at `#ws-page`, with the
     * side nav out of band and the context bar too only when it changed. When it did not, the bar's
     * status is told to refresh in place. The page the click came from says which bar it shows in
     * the `X-Epistola-Shell` header; from a page without the workspace the whole body is swapped.
     */
    private fun boostInsideWorkspace(request: HttpServletRequest, response: HttpServletResponse, modelAndView: ModelAndView, workspace: Workspace) {
        if (request.getHeader("HX-Boosted") != "true") return
        val shown = request.getHeader(SHELL_HEADER) ?: return
        if (!shown.startsWith("nav:")) return
        val barChanged = shown != "nav:${workspace.barKey}"
        modelAndView.viewName = "layout/boosted"
        modelAndView.addObject("boosted", true)
        modelAndView.addObject("barChanged", barChanged)
        response.setHeader("HX-Retarget", "#ws-page")
        response.setHeader("HX-Reswap", "outerHTML")
        if (!barChanged) response.setHeader("HX-Trigger", "ws-status-refresh")
    }

    private companion object {
        /** The bar the page shows, as `Workspace.barKey`; sent by nav.js on a boosted navigation. */
        const val SHELL_HEADER = "X-Epistola-Shell"
    }
}
