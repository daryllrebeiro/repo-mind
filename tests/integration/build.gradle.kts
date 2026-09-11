plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(project(":core:model"))
    testImplementation(project(":core:scanner"))
    testImplementation(project(":core:classpath"))
    testImplementation(project(":core:config"))
    testImplementation(project(":core:index"))
    testImplementation(project(":language:java"))
    testImplementation(project(":storage:sqlite"))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
}
