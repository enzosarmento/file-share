import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(17)

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val desktopMain by getting
        val desktopTest by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.logback.classic)
        }

        desktopTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.server.test.host)
        }
    }
}

android {
    namespace = "br.edu.fileshare"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "br.edu.fileshare"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-M0"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        // Entrypoint do aplicativo grafico Desktop (Cliente GUI)
        mainClass = "br.edu.fileshare.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "FileShare"
            packageVersion = "1.0.0"
        }
    }
}

// Task dedicada para executar o Servidor Desktop de forma independente (headless/CLI),
// atendendo ao requisito arquitetural 4.1 (separacao entre interface grafica e servico de rede).
tasks.register<JavaExec>("runDesktopServer") {
    group = "application"
    description = "Executa o servidor de rede Desktop de forma independente da interface grafica."
    mainClass.set("br.edu.fileshare.desktop.server.ServerMainKt")
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = files(
        desktopCompilation.output.allOutputs,
        configurations.getByName("desktopRuntimeClasspath")
    )
    standardInput = System.`in`
}

// Gera JAR executavel independente para o Servidor Desktop (Headless/CLI).
tasks.register<Jar>("packageDesktopServerJar") {
    group = "distribution"
    description = "Empacota o Servidor Desktop Independente em um JAR executavel."
    archiveBaseName.set("fileshare-server")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "br.edu.fileshare.desktop.server.ServerMainKt"
    }
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    from(desktopCompilation.output.allOutputs)
    from({
        configurations.getByName("desktopRuntimeClasspath")
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST")
}

