package app.epistola.suite.templates.commands.variants

import app.epistola.suite.common.ids.VariantId

class DefaultVariantDeletionException(val variantId: VariantId) :
    RuntimeException(
        "Cannot delete variant '$variantId' because it is the default. Reassign default first.",
    )
