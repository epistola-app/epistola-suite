// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class ForbiddenTestController {

    @GetMapping("/api/test/forbidden")
    @PreAuthorize("hasRole('NON_EXISTENT')")
    fun forbidden() = mapOf("ok" to true)
}
