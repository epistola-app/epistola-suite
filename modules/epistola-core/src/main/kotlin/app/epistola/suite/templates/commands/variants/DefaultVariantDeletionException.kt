package app.epistola.suite.templates.commands.variants

import app.epistola.suite.common.ids.VariantKey

class DefaultVariantDeletionException(val variantId: VariantKey) :
    RuntimeException(
        "Cannot delete variant '$variantId' because it is the default. Reassign default first.",
    )
