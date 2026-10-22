import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Bettet die TV-Seite (tv/index.html) als Kotlin-Quelltext ein, damit Android und iOS sie gleich ausliefern. */
val embedTvPage by tasks.registering {
    val input = layout.projectDirectory.file("tv/index.html")
    val outDir = layout.buildDirectory.dir("generated/tv")
    inputs.file(input)
    outputs.dir(outDir)
    doLast {
        val parts = input.asFile.readText().chunked(8_000).joinToString(",\n") { part ->
            "        \"" + part.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
                .replace("\n", "\\n").replace("\t", "\\t").replace("\r", "") + "\""
        }
        val file = outDir.get().file("de/ballosv/whoami/tv/TvPage.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package de.ballosv.whoami.tv\n\n" +
                "// Erzeugt aus shared/tv/index.html – nicht von Hand ändern.\n" +
                "internal object TvPage {\n" +
                "    val html: String by lazy { PARTS.joinToString(\"\") }\n\n" +
                "    private val PARTS = listOf(\n$parts,\n    )\n}\n",
        )
    }
}

kotlin {
    jvmToolchain(17)

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.addAll("-Xexpect-actual-classes", "-opt-in=kotlinx.cinterop.ExperimentalForeignApi")
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(embedTvPage)
            dependencies {
                api(project(":core"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(libs.mp.lifecycle.viewmodel.compose)
                implementation(libs.mp.lifecycle.runtime.compose)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.network)
            }
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.play.services.nearby)
            implementation(libs.mlkit.face.detection)
            implementation(libs.play.services.base)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "de.ballosv.whoami.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
