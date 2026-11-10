import com.strumenta.antlrkotlin.gradle.AntlrKotlinTask
import java.io.File

plugins {
    id("karandash.kmp-library")
    alias(libs.plugins.antlr.kotlin)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.antlr.kotlin.runtime)
            implementation(project(":core:api"))

            implementation(project(":core:config"))
            implementation(project(":core:conditions"))
            implementation(project(":core:platform"))
            implementation(project(":core:interpolation"))
        }
        commonTest.dependencies {
            implementation(project(":plugins:sdk"))
            implementation(project(":core:validation"))
            implementation(kotlin("test"))
            implementation(project(":test-fixtures"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The ANTLR parser/lexer is generated: its coverage says nothing about this module's code.
kover {
    reports {
        filters {
            excludes {
                packages("io.karandash.parser.generated")
            }
        }
    }
}

plugins.withType<org.jlleitschuh.gradle.ktlint.KtlintPlugin> {
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        filter {
            include("**/src/**/*.kt")
            include("**/src/**/*.kts")
        }
    }
}

val generateKotlinGrammarSource by tasks.register<AntlrKotlinTask>("generateKotlinGrammarSource") {
    dependsOn("cleanGenerateKotlinGrammarSource")

    val outputDir = layout.buildDirectory
        .dir("generated-src/antlr/commonMain/kotlin")
        .get()
        .asFile
    outputDirectory = outputDir

    source = fileTree("src/commonAntlr/antlr") {
        include("**/*.g4")
    }

    packageName = "io.karandash.parser.generated"

    arguments = listOf("-visitor", "-no-listener")

    doFirst {
        outputDir.mkdirs()
    }

    doLast {
        val editorConfigFile = File(outputDir, ".editorconfig")
        editorConfigFile.writeText(
            "[*.kt]\n" +
                "ktlint_standard_no-wildcard-imports = disabled\n" +
                "ktlint_standard_comment-wrapping = disabled\n" +
                "ktlint_standard_comment-spacing = disabled\n" +
                "ktlint_standard_argument-list-wrapping = disabled\n" +
                "ktlint_standard_blank-line-before-declaration = disabled\n" +
                "ktlint_standard_class-signature = disabled\n" +
                "ktlint_standard_curly-spacing = disabled\n" +
                "ktlint_standard_function-expression-body = disabled\n" +
                "ktlint_standard_function-signature = disabled\n" +
                "ktlint_standard_indent = disabled\n" +
                "ktlint_standard_keyword-spacing = disabled\n" +
                "ktlint_standard_no-blank-line-before-rbrace = disabled\n" +
                "ktlint_standard_no-consecutive-blank-lines = disabled\n" +
                "ktlint_standard_no-consecutive-comments = disabled\n" +
                "ktlint_standard_no-single-line-block-comment = disabled\n" +
                "ktlint_standard_no-trailing-spaces = disabled\n" +
                "ktlint_standard_no-unused-imports = disabled\n" +
                "ktlint_standard_paren-spacing = disabled\n" +
                "ktlint_standard_spacing-between-declarations-with-comments = disabled\n" +
                "ktlint_standard_try-catch-finally-spacing = disabled\n"
        )

        fileTree(outputDir).matching { include("**/*.kt") }.forEach { generatedFile ->
            val original = generatedFile.readText()
            val sanitized = original.replace("@Suppress(\"UNSAFE_CALL\")\n", "")
            if (sanitized != original) {
                generatedFile.writeText(sanitized)
            }
        }
    }
}

kotlin.sourceSets.commonMain {
    kotlin.srcDir(tasks.named("generateKotlinGrammarSource"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateKotlinGrammarSource)
}
