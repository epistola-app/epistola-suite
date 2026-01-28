rootProject.name = "epistola-suite"

include(":apps:epistola")
include(":modules:vendor")
include(":modules:editor")
include(":modules:schema-manager")
include(":modules:api-spec")
include(":modules:api-server")
include(":modules:api-client") // Uses Jackson 2.x for external consumer compatibility
include(":modules:template-model")
include(":modules:generation")
