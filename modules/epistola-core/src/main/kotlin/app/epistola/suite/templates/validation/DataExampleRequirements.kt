// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.validate

/** Enforces the authoring invariant without invalidating readable legacy contract versions. */
fun requireAtLeastOneDataExample(dataExamples: Collection<DataExample>) {
    validate(
        field = "dataExamples",
        value = dataExamples.isNotEmpty(),
        code = ValidationCode.DATA_EXAMPLE_REQUIRED,
    ) {
        "At least one data example is required"
    }
}
