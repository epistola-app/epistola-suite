// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.common.ids.TenantKey
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
 * (`navGroups`, `activeNavSection`) when rendering `layout/shell`.
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
        val tenantId = modelAndView.model["tenantId"]?.toString() ?: return
        val auth = modelAndView.model["auth"] as AuthContext
        val context = UiRequestContext(TenantKey.of(tenantId)) { auth.has(it) }
        val nav = navMenuAggregator.build(context, request.requestURI)
        modelAndView.addObject("navGroups", nav.groups)
        modelAndView.addObject("activeNavSection", nav.activeNavSection)
    }
}
