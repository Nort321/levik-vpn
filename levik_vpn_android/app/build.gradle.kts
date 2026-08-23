import java.io.File
import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val source = rootProject.file("local.properties")
    if (source.isFile) {
        source.inputStream().use(::load)
    }
}

fun buildProperty(name: String, fallback: String): String =
    providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name)
        ?: System.getenv(name.replace('.', '_').uppercase())
        ?: fallback

val cabinetBaseUrl = buildProperty("levik.cabinetBaseUrl", "https://leviknet.com")
val playIntegrityCloudProjectNumber =
    buildProperty("levik.playIntegrityCloudProjectNumber", "0").toLongOrNull() ?: 0L
val libXrayAar = layout.projectDirectory.file("libs/libXray.aar").asFile
val expectedLibXraySha256 = "4708a361a74f7e955635dbe3661cefb459bdc867423c3b1826a2c5a6ea4ac77d"
val releaseSigningStoreFilePath = buildProperty("levik.signing.storeFile", "")
val releaseSigningStorePassword = buildProperty("levik.signing.storePassword", "")
val releaseSigningKeyAlias = buildProperty("levik.signing.keyAlias", "")
val releaseSigningKeyPassword = buildProperty("levik.signing.keyPassword", "")
val releaseSigningStoreFile = releaseSigningStoreFilePath
    .takeIf(String::isNotBlank)
    ?.let { path -> rootProject.file(path) }
val releaseSigningInputsPresent =
    releaseSigningStoreFile?.isFile == true &&
        releaseSigningStorePassword.isNotBlank() &&
        releaseSigningKeyAlias.isNotBlank() &&
        releaseSigningKeyPassword.isNotBlank()

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

android {
    namespace = "com.leviknet.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.leviknet.vpn"
        minSdk = 26
        targetSdk = 36
        versionCode = 19
        versionName = "1.9.0"

        buildConfigField("String", "CABINET_BASE_URL", "\"${cabinetBaseUrl.trimEnd('/')}\"")
        buildConfigField("String", "LIBXRAY_VERSION", "\"v26.7.28\"")
        buildConfigField(
            "long",
            "PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER",
            "${playIntegrityCloudProjectNumber}L",
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        val abiFilterProperty = providers.gradleProperty("levik.abiFilters").orNull
        if (abiFilterProperty != null) {
            ndk {
                //noinspection ChromeOsAbiSupport -- Optional filters produce device-specific APKs.
                abiFilters += abiFilterProperty.split(',').map(String::trim).filter(String::isNotEmpty)
            }
        }
    }

    signingConfigs {
        create("release") {
            if (releaseSigningStoreFile != null) {
                storeFile = releaseSigningStoreFile
                storePassword = releaseSigningStorePassword
                keyAlias = releaseSigningKeyAlias
                keyPassword = releaseSigningKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningInputsPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Both distributions intentionally inherit the same applicationId until the
        // production package/signing migration decision is backed by real install data.
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_PLAY_DISTRIBUTION", "true")
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")
            buildConfigField("boolean", "EXTERNAL_PURCHASES_ENABLED", "false")
            buildConfigField("boolean", "PLAY_INTEGRITY_ENABLED", "true")
        }
        create("direct") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_PLAY_DISTRIBUTION", "false")
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "true")
            buildConfigField("boolean", "EXTERNAL_PURCHASES_ENABLED", "true")
            buildConfigField("boolean", "PLAY_INTEGRITY_ENABLED", "false")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        localeFilters += listOf("en", "ru")
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xannotation-default-target=param-property",
        )
    }
}

val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails release artifact builds when production signing inputs are incomplete."

    doLast {
        val missingInputs = buildList {
            if (releaseSigningStoreFilePath.isBlank()) add("levik.signing.storeFile")
            if (releaseSigningStorePassword.isBlank()) add("levik.signing.storePassword")
            if (releaseSigningKeyAlias.isBlank()) add("levik.signing.keyAlias")
            if (releaseSigningKeyPassword.isBlank()) add("levik.signing.keyPassword")
        }
        check(missingInputs.isEmpty()) {
            "Release signing is incomplete. Configure the required signing properties: " +
                missingInputs.joinToString(", ")
        }
        check(releaseSigningStoreFile?.isFile == true) {
            "The configured release signing store does not exist or is not a regular file."
        }
    }
}

val validatePlayReleaseConfiguration by tasks.registering {
    group = "verification"
    description = "Fails Play release artifact builds without a configured Play Integrity project."

    doLast {
        check(playIntegrityCloudProjectNumber > 0L) {
            "Play release builds require levik.playIntegrityCloudProjectNumber."
        }
    }
}

fun isReleaseArtifactTask(taskName: String): Boolean =
    taskName.contains("Release", ignoreCase = true) &&
        listOf("assemble", "bundle", "package", "sign").any { prefix ->
            taskName.startsWith(prefix, ignoreCase = true)
        }

tasks.configureEach {
    if (isReleaseArtifactTask(name)) {
        dependsOn(validateReleaseSigning)
    }
    if (isReleaseArtifactTask(name) && name.contains("PlayRelease", ignoreCase = true)) {
        dependsOn(validatePlayReleaseConfiguration)
    }
    if (name.contains("Release", ignoreCase = true)) {
        doFirst {
            check(libXrayAar.isFile) {
                "Official libXray v26.7.28 AAR is required at app/libs/libXray.aar. See app/libs/README.md."
            }
            check(sha256(libXrayAar) == expectedLibXraySha256) {
                "libXray.aar SHA-256 does not match pinned official v26.7.28."
            }
        }
    }
}

dependencies {
    implementation(files("libs/libXray.aar"))

    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    add("playImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    add("playImplementation", "com.google.android.play:integrity:1.6.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
