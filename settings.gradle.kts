rootProject.name = "repomind"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":core:model")
include(":core:scanner")
include(":core:classpath")
include(":language:java")
include(":storage:sqlite")
include(":cli")

project(":cli").projectDir = file("apps/cli")
