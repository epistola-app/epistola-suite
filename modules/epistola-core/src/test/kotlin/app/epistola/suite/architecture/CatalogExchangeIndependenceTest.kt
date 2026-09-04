// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * The catalog domain must not know that Exchange exists.
 *
 * This is the whole point of [app.epistola.suite.catalog.CatalogReleasePublicationPort]: publishing
 * is optional, remote, and can be absent entirely, so catalog decides *whether* to publish from its
 * own policy and the Exchange side decides how and where. ADR 0018 records the decision and
 * CLAUDE.md restates it, but until now nothing enforced it — one import in a hurry would undo the
 * seam and no build would notice.
 *
 * Checked against bytecode rather than by scanning imports on purpose. A fully qualified reference
 * (`app.epistola.suite.exchange.Foo(...)` written inline) carries no import statement, and a rule
 * that a careful grep can pass while the code still depends on Exchange is not a rule.
 *
 * The dependency in the other direction is expected and unrestricted: `exchange` reads catalogs,
 * builds their archives, and implements the port.
 */
class CatalogExchangeIndependenceTest {
    private val coreClasses: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("app.epistola.suite")

    @Test
    fun `the catalog domain does not depend on the Exchange integration`() {
        noClasses()
            .that()
            .resideInAPackage("app.epistola.suite.catalog..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("app.epistola.suite.exchange..")
            .because(
                "catalog records publication intent through CatalogReleasePublicationPort so the Exchange " +
                    "integration stays optional and absent-able (ADR 0018); a direct reference would make the " +
                    "catalog domain unusable without it",
            ).check(coreClasses)
    }
}
