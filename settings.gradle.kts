rootProject.name = "epistola-suite"

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent {
                snapshotsOnly()
            }
        }
    }
}

include(":apps:epistola")
include(":modules:epistola-core")
include(":modules:loadtest")
include(":modules:editor")
include(":modules:rest-api")
include(":modules:generation")
include(":modules:feedback")
