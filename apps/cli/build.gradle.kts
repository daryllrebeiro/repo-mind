plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass = "dev.repomind.cli.MainKt"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:scanner"))
    implementation(project(":core:classpath"))
    implementation(project(":language:java"))
    implementation(libs.picocli)
    implementation(libs.kotlinx.serialization.json)
}
