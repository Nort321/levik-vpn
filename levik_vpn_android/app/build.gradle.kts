import java.io.File
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
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

fun protectedBuildProperty(name: String, environmentName: String): String =
    providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name)
        ?: System.getenv(environmentName)
        ?: ""

data class ReleaseSigningInputs(
    val distribution: String,
    val propertyPrefix: String,
    val storeFilePath: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
    val storeFile: File?,
) {
    val inputsPresent: Boolean
        get() = storeFile?.isFile == true &&
            storePassword.isNotBlank() &&
            keyAlias.isNotBlank() &&
            keyPassword.isNotBlank()

    fun missingPropertyNames(): List<String> = buildList {
        if (storeFilePath.isBlank()) add("$propertyPrefix.storeFile")
        if (storePassword.isBlank()) add("$propertyPrefix.storePassword")
        if (keyAlias.isBlank()) add("$propertyPrefix.keyAlias")
        if (keyPassword.isBlank()) add("$propertyPrefix.keyPassword")
    }
}

fun releaseSigningInputs(distribution: String): ReleaseSigningInputs {
    val propertyPrefix = "levik.$distribution.signing"
    val environmentPrefix = "LEVIK_${distribution.uppercase()}_SIGNING"
    val storeFilePath = protectedBuildProperty(
        "$propertyPrefix.storeFile",
        "${environmentPrefix}_STORE_FILE",
    )
    return ReleaseSigningInputs(
        distribution = distribution,
        propertyPrefix = propertyPrefix,
        storeFilePath = storeFilePath,
        storePassword = protectedBuildProperty(
            "$propertyPrefix.storePassword",
            "${environmentPrefix}_STORE_PASSWORD",
        ),
        keyAlias = protectedBuildProperty(
            "$propertyPrefix.keyAlias",
            "${environmentPrefix}_KEY_ALIAS",
        ),
        keyPassword = protectedBuildProperty(
            "$propertyPrefix.keyPassword",
            "${environmentPrefix}_KEY_PASSWORD",
        ),
        storeFile = storeFilePath.takeIf(String::isNotBlank)?.let(rootProject::file),
    )
}

val cabinetBaseUrl = buildProperty("levik.cabinetBaseUrl", "https://leviknet.com")
val playIntegrityCloudProjectNumber =
    buildProperty("levik.playIntegrityCloudProjectNumber", "0").toLongOrNull() ?: 0L
val directUpdateManifestPublicKey =
    providers.gradleProperty("levik.updateManifestPublicKey").orNull
        ?: localProperties.getProperty("levik.updateManifestPublicKey")
        ?: System.getenv("LEVIK_UPDATE_MANIFEST_PUBLIC_KEY")
        ?: ""
val directUpdateSigningCertificateSha256 =
    providers.gradleProperty("levik.updateSigningCertificateSha256").orNull
        ?: localProperties.getProperty("levik.updateSigningCertificateSha256")
        ?: System.getenv("LEVIK_UPDATE_SIGNING_CERTIFICATE_SHA256")
        ?: ""
val libXrayAar = layout.projectDirectory.file("libs/libXray.aar").asFile
val expectedLibXraySha256 = "4708a361a74f7e955635dbe3661cefb459bdc867423c3b1826a2c5a6ea4ac77d"
val directReleaseSigning = releaseSigningInputs("direct")
val playReleaseSigning = releaseSigningInputs("play")

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

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }

fun buildConfigString(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .let { escaped -> "\"$escaped\"" }

fun normalizedCertificateSha256(value: String): String =
    value.trim().replace(":", "").lowercase()

android {
    namespace = "com.leviknet.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.leviknet.vpn"
        minSdk = 26
        targetSdk = 36
        versionCode = 28
        versionName = rootProject.version.toString()

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
        create("directRelease") {
            if (directReleaseSigning.inputsPresent) {
                storeFile = directReleaseSigning.storeFile
                storePassword = directReleaseSigning.storePassword
                keyAlias = directReleaseSigning.keyAlias
                keyPassword = directReleaseSigning.keyPassword
            }
        }
        create("playRelease") {
            if (playReleaseSigning.inputsPresent) {
                storeFile = playReleaseSigning.storeFile
                storePassword = playReleaseSigning.storePassword
                keyAlias = playReleaseSigning.keyAlias
                keyPassword = playReleaseSigning.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
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
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Both distributions intentionally inherit the same applicationId until the
        // production package/signing migration decision is backed by real install data.
        create("play") {
            dimension = "distribution"
            signingConfig = signingConfigs.getByName("playRelease")
            buildConfigField("boolean", "IS_PLAY_DISTRIBUTION", "true")
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")
            buildConfigField("boolean", "EXTERNAL_PURCHASES_ENABLED", "false")
            buildConfigField("boolean", "PLAY_INTEGRITY_ENABLED", "true")
        }
        create("direct") {
            dimension = "distribution"
            signingConfig = signingConfigs.getByName("directRelease")
            buildConfigField("boolean", "IS_PLAY_DISTRIBUTION", "false")
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "true")
            buildConfigField("boolean", "EXTERNAL_PURCHASES_ENABLED", "true")
            buildConfigField("boolean", "PLAY_INTEGRITY_ENABLED", "false")
            buildConfigField(
                "String",
                "DIRECT_UPDATE_MANIFEST_PUBLIC_KEY",
                buildConfigString(directUpdateManifestPublicKey.trim()),
            )
            buildConfigField(
                "String",
                "DIRECT_UPDATE_SIGNING_CERTIFICATE_SHA256",
                buildConfigString(normalizedCertificateSha256(directUpdateSigningCertificateSha256)),
            )
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

fun requireReleaseSigning(inputs: ReleaseSigningInputs) {
    val missingInputs = inputs.missingPropertyNames()
    check(missingInputs.isEmpty()) {
        "${inputs.distribution.replaceFirstChar(Char::uppercase)} release signing is incomplete. " +
            "Configure the required signing properties: ${missingInputs.joinToString(", ")}"
    }
    check(inputs.storeFile?.isFile == true) {
        "The configured ${inputs.distribution} release signing store does not exist or is not a regular file."
    }
}

val validateDirectReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails Direct release artifacts without independent Direct signing inputs."
    doLast { requireReleaseSigning(directReleaseSigning) }
}

val validatePlayReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails Play release artifacts without independent Play signing inputs."
    doLast { requireReleaseSigning(playReleaseSigning) }
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

fun validateDirectUpdatePublicKey() {
    val publicKey = try {
        val encoded = Base64.getDecoder().decode(directUpdateManifestPublicKey.trim())
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
    } catch (error: Exception) {
        throw GradleException(
            "levik.updateManifestPublicKey must be a Base64 X.509 ECDSA P-256 public key.",
            error,
        )
    }
    val expectedP256Parameters = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }
    check(
        publicKey is ECPublicKey &&
            publicKey.params.curve == expectedP256Parameters.curve &&
            publicKey.params.generator == expectedP256Parameters.generator &&
            publicKey.params.order == expectedP256Parameters.order &&
            publicKey.params.cofactor == expectedP256Parameters.cofactor,
    ) {
        "levik.updateManifestPublicKey must use the P-256 curve."
    }
}

fun releaseSigningCertificateSha256(inputs: ReleaseSigningInputs): String {
    check(inputs.inputsPresent && inputs.storeFile != null) {
        "Direct OTA validation requires complete release signing inputs."
    }
    val signingStoreFile = requireNotNull(inputs.storeFile)
    for (storeType in listOf("JKS", "PKCS12")) {
        try {
            val keyStore = KeyStore.getInstance(storeType)
            signingStoreFile.inputStream().use { input ->
                keyStore.load(input, inputs.storePassword.toCharArray())
            }
            val certificate = keyStore.getCertificate(inputs.keyAlias) ?: continue
            return sha256(certificate.encoded)
        } catch (_: Exception) {
            // Try the other common Android keystore format without exposing secret-bearing errors.
        }
    }
    throw GradleException("Unable to read the configured release signing certificate.")
}

val validateDirectReleaseOtaConfiguration by tasks.registering {
    group = "verification"
    description = "Fails Direct release artifacts without pinned OTA verification material."
    dependsOn(validateDirectReleaseSigning)

    doLast {
        check(directUpdateManifestPublicKey.isNotBlank()) {
            "Direct release builds require levik.updateManifestPublicKey."
        }
        validateDirectUpdatePublicKey()
        val configuredCertificate = normalizedCertificateSha256(
            directUpdateSigningCertificateSha256,
        )
        check(configuredCertificate.matches(Regex("^[0-9a-f]{64}$"))) {
            "Direct release builds require levik.updateSigningCertificateSha256."
        }
        check(configuredCertificate == releaseSigningCertificateSha256(directReleaseSigning)) {
            "Direct OTA certificate pin does not match the configured release signing certificate."
        }
    }
}

val verifyDirectReleaseRuntimeClasspath by tasks.registering {
    group = "verification"
    description = "Fails when the Direct release runtime contains Google Play dependencies."

    doLast {
        val forbidden = configurations
            .getByName("directReleaseRuntimeClasspath")
            .incoming
            .resolutionResult
            .allComponents
            .mapNotNull { component -> component.moduleVersion }
            .filter { module ->
                module.group in setOf(
                    "com.google.android.play",
                    "com.google.android.gms",
                    "com.google.firebase",
                ) || (
                    module.group == "org.jetbrains.kotlinx" &&
                        module.name == "kotlinx-coroutines-play-services"
                    )
            }
            .map { module -> "${module.group}:${module.name}:${module.version}" }
            .distinct()
            .sorted()
        check(forbidden.isEmpty()) {
            "directReleaseRuntimeClasspath contains forbidden Google Play dependencies:\n - " +
                forbidden.joinToString("\n - ")
        }
    }
}

fun isReleaseArtifactTask(taskName: String, distribution: String): Boolean = taskName in setOf(
    "assemble${distribution}Release",
    "bundle${distribution}Release",
    "package${distribution}Release",
    "package${distribution}ReleaseBundle",
    "package${distribution}ReleaseUniversalApk",
    "sign${distribution}ReleaseBundle",
)

tasks.configureEach {
    if (isReleaseArtifactTask(name, "Play")) {
        dependsOn(validatePlayReleaseSigning, validatePlayReleaseConfiguration)
    }
    if (isReleaseArtifactTask(name, "Direct")) {
        dependsOn(
            validateDirectReleaseSigning,
            validateDirectReleaseOtaConfiguration,
            verifyDirectReleaseRuntimeClasspath,
        )
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
    implementation("androidx.browser:browser:1.10.0")
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
