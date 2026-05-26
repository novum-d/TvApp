import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
}

tasks.register("installPreCommitHook") {
    group = "verification"
    description = "Installs the repository pre-commit hook into .git/hooks."

    doLast {
        val hookSource = rootProject.file("scripts/git-hooks/pre-commit")
        val hookTarget = rootProject.file(".git/hooks/pre-commit")

        copy {
            from(hookSource)
            into(hookTarget.parentFile)
        }
        hookTarget.setExecutable(true)
    }
}

tasks.register("ktlintCheck") {
    group = "verification"
    description = "Compatibility task for existing workflow and documentation; delegates to detekt formatting checks."
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    dependencies {
        add("detektPlugins", libsCatalog.findLibrary("detekt-twitter-compose").get())
        add("detektPlugins", libsCatalog.findLibrary("detekt-formatting").get())
    }

    configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = JavaVersion.VERSION_11.toString()
        setSource(
            fileTree(projectDir) {
                include("src/main/java/**/*.kt")
                include("src/test/java/**/*.kt")
                include("src/androidTest/java/**/*.kt")
                exclude("**/build/**")
            }
        )

        reports {
            html.required.set(true)
            xml.required.set(false)
            txt.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
        }
    }

    tasks.register<Detekt>("detektFormat") {
        group = "verification"
        description = "Auto-corrects Kotlin formatting issues with detekt formatting rules."
        autoCorrect = true
        buildUponDefaultConfig = false
        config.setFrom(rootProject.files("config/detekt/formatting.yml"))
        jvmTarget = JavaVersion.VERSION_11.toString()
        setSource(
            fileTree(projectDir) {
                include("src/main/java/**/*.kt")
                include("src/test/java/**/*.kt")
                include("src/androidTest/java/**/*.kt")
                exclude("**/build/**")
            }
        )
        reports {
            html.required.set(true)
            xml.required.set(false)
            txt.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
        }
    }

    val detektFormatCheck = tasks.register<Detekt>("detektFormatCheck") {
        group = "verification"
        description = "Checks Kotlin formatting with detekt formatting rules without modifying files."
        autoCorrect = false
        buildUponDefaultConfig = false
        config.setFrom(rootProject.files("config/detekt/formatting.yml"))
        jvmTarget = JavaVersion.VERSION_11.toString()
        setSource(
            fileTree(projectDir) {
                include("src/main/java/**/*.kt")
                include("src/test/java/**/*.kt")
                include("src/androidTest/java/**/*.kt")
                exclude("**/build/**")
            }
        )
        reports {
            html.required.set(true)
            xml.required.set(false)
            txt.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
        }
    }

    rootProject.tasks.named("ktlintCheck") {
        dependsOn(detektFormatCheck)
    }
}
