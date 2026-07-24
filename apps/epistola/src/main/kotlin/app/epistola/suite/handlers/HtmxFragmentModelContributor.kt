// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.htmx.FragmentModelContributor
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

/**
 * Host-app implementation of [FragmentModelContributor]: injects the common
 * tenant-view attributes (`auth`, `isManager`, feature flags, footer chrome) into
 * HTMX multi-fragment / OOB renders, which bypass [ShellModelInterceptor].
 *
 * Shares [CommonViewModel] with the interceptor so the two render paths can't
 * drift. Reads the tenant from the fragment model (same as the interceptor reads
 * it from the view model), so the `request` argument is unused here.
 */
@Component
class HtmxFragmentModelContributor(
    private val commonViewModel: CommonViewModel,
) : FragmentModelContributor {
    override fun contribute(request: HttpServletRequest, fragmentModel: Map<String, Any?>): Map<String, Any?> = commonViewModel.attributes(fragmentModel)
}
