rootProject.name = "repomind"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":core:model")
include(":core:scanner")
include(":core:classpath")
include(":core:config")
include(":core:eval")
include(":core:graph")
include(":core:impact")
include(":core:query")
include(":core:index")
include(":core:rules")
include(":language:java")
include(":storage:sqlite")
include(":cli")

project(":cli").projectDir = file("apps/cli")
include(":mcp-server")
project(":mcp-server").projectDir = file("apps/mcp-server")
