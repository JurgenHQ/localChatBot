import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            // El link intermedio de Gradle (linkDebugFrameworkIos*) tolera símbolos sin
            // resolver, pero el link final del binario de la app en Xcode no: SQLDelight
            // native-driver llama a sqlite3 vía cinterop sin enlazar la lib del sistema.
            linkerOpts += "-lsqlite3"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(compose.materialIconsExtended)

            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.navigation.compose)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)

            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)

            implementation(libs.markdown.renderer.m3)

            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
        }

        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activityCompose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.peekaboo.image.picker)
            implementation(libs.pdfbox.android)
            implementation(libs.sqldelight.android.driver)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.peekaboo.image.picker)
            implementation(libs.sqldelight.native.driver)
        }

        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.server.status.pages)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.pdfbox)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.jna.platform)
        }
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
}

android {
    namespace = "com.localchatbot"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.localchatbot"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

sqldelight {
    databases {
        create("LocalChatBotDatabase") {
            packageName.set("com.localchatbot.data.local.db")
            // Snapshots `.db` del esquema por versión. Son lo que hace que verifyMigrations
            // sirva de algo: sin ellos la tarea falla por falta de base contra la que aplicar
            // los `.sqm`. Se commitean; `1.db` es el esquema tal como quedó en las bases de
            // los usuarios antes de que existiera el versionado.
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.localchatbot.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "LocalChatBot"
            packageVersion = "1.0.6"
            description = "Chat con un modelo LLM local en tu red"
            vendor = "LocalChatBot"
            // jlink solo incluye módulos detectados por análisis estático (jdeps); el driver
            // JDBC de SQLite carga java.sql.DriverManager por reflexión (ServiceLoader), así
            // que jdeps no lo ve y el runtime empaquetado queda sin el módulo -> NoClassDefFoundError
            // en el binario instalado (no se reproduce con `./gradlew run`, que usa el JDK completo).
            modules("java.sql")

            macOS {
                bundleID = "com.localchatbot.desktop"
                iconFile.set(project.file("src/desktopMain/resources/AppIcon.icns"))
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/AppIcon.ico"))
                upgradeUuid = "e3f2a1b0-4c5d-6e7f-8a9b-0c1d2e3f4a5b"
            }
            linux {
                packageName = "localchatbot"
            }
            windows {
                // UUID fijo: jpackage lo usa como UpgradeCode del MSI. Estable →
                // instalador reemplaza la versión anterior en vez de instalar al lado.
                upgradeUuid = "CDBBFAEA-613E-44EF-8F84-B3A66E4F5CF7"
                menuGroup = "LocalChatBot"
                perUserInstall = true
                // Acceso directo en el escritorio + entrada en menú inicio.
                shortcut = true
            }
        }
    }
}
