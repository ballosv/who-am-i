import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Bettet die erzeugte Personenliste als Kotlin-Quelltext ein: So steht sie auf Android und iOS
 * gleichermaßen zur Verfügung, ohne plattformabhängiges Laden von Ressourcen.
 */
val embedCelebrities by tasks.registering {
    val input = layout.projectDirectory.file("data/celebrities_wikidata.tsv")
    val outDir = layout.buildDirectory.dir("generated/celebrities")
    inputs.file(input)
    outputs.dir(outDir)
    doLast {
        val text = input.asFile.readText()
        // Einzelne String-Konstanten dürfen auf der JVM höchstens 64 KB groß sein.
        val parts = text.chunked(8_000).joinToString(",\n") { part ->
            "        \"" + part.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
                .replace("\n", "\\n").replace("\t", "\\t").replace("\r", "") + "\""
        }
        val file = outDir.get().file("de/ballosv/whoami/core/CelebrityTsv.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package de.ballosv.whoami.core\n\n" +
                "// Erzeugt aus core/data/celebrities_wikidata.tsv – nicht von Hand ändern.\n" +
                "internal object CelebrityTsv {\n" +
                "    val text: String by lazy { PARTS.joinToString(\"\") }\n\n" +
                "    private val PARTS = listOf(\n$parts,\n    )\n}\n",
        )
    }
}

kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(embedCelebrities)
            dependencies {
                api(libs.kotlinx.serialization.json)
            }
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}
