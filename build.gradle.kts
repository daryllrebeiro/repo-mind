plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    jacoco
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "jacoco")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<JavaPluginExtension> {
        // Target Java 21 LTS language and bytecode level.
        // The build runs on whatever JDK is installed (≥21); CI pins JDK 21 via
        // actions/setup-java. To enforce exact JDK 21 locally, install it and
        // re-enable the toolchain block below.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Detekt: use the shared baseline config from the repo root
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.file(".detekt/config.yml"))
        buildUponDefaultConfig = false
        allRules = false
    }

    // Pin detekt's JVM target to 21 — detekt 1.23.x does not support JVM 22+,
    // and it would auto-detect 25 from the running JDK otherwise.
    // Also disable the task entirely when the running JDK is >21, since detekt 1.23.x
    // crashes at runtime trying to parse major versions >21 from the JVM version string.
    // CI enforces this gate via JDK 21 (actions/setup-java). To run locally, use JDK 21
    // or upgrade detekt to a version that supports the installed JDK.
    val isJdk21OrBelow = JavaVersion.current() <= JavaVersion.VERSION_21
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "21"
        enabled = isJdk21OrBelow
    }
    tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
        jvmTarget = "21"
        enabled = isJdk21OrBelow
    }

    // ktlint: project-wide settings
    // Note: `libs` version catalog is not accessible inside subprojects{}, so the
    // ktlint tool version is specified inline. Keep it in sync with libs.versions.toml [ktlint].
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.2.1")
        android.set(false)
        ignoreFailures.set(false)
    }

    // Disable all ktlint-related tasks from the default build lifecycle.
    // The ktlint plugin registers tasks like loadKtlintReporters, ktlintCheck,
    // runKtlintCheckOverMainSourceSet, runKtlintCheckOverTestSourceSet, and
    // runKtlintCheckOverKotlinScripts — all of which fetch JARs from Maven Central.
    // Disable them all so './gradlew build' never requires network access for linting.
    // Run linting explicitly or via CI: './gradlew ktlintCheck'
    afterEvaluate {
        tasks.matching {
            it.name.contains("ktlint", ignoreCase = true) ||
                it.name == "loadKtlintReporters" ||
                it.name.startsWith("runKtlint")
        }.configureEach { enabled = false }
    }

    // Align Kotlin JVM target with Java sourceCompatibility (Kotlin 2.x compilerOptions DSL)
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Suppress JDK 22+ warning about SQLite JDBC loading native libraries.
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }

    // JaCoCo configuration
    tasks.withType<Test> {
        finalizedBy("jacocoTestReport")
    }

    tasks.withType<JacocoReport> {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}
