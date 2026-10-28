import java.security.MessageDigest
import java.util.Properties
import org.gradle.api.tasks.testing.Test
import com.g2ray.gradle.VerifyHevBundleTask
import com.g2ray.gradle.VerifyLibXrayBuildContractTask
import com.g2ray.gradle.VerifyLibXrayBundleTask
import com.g2ray.gradle.VerifyPersistedJsonSchemaTask
import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val libXrayAar = file("libs/libXray.aar")
val libXrayPropertiesFile = file("libs/libXray.properties")
val libXrayBuildScript = rootProject.file("scripts/build-libxray-aar.ps1")
val libXrayControllerPatch = rootProject.file("scripts/patches/libxray-android-controller.patch")
val libXrayObserverPatch = rootProject.file("scripts/patches/xray-observer-detached-snapshot.patch")
val libXrayMeasurePatch = rootProject.file("scripts/patches/libxray-measure-status.patch")
val hevMetadataFile = file("libs/hev.properties")
val hevJniLibsDirectory = file("src/main/jniLibs")
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
val releaseKeystorePath = System.getenv("G2RAY_RELEASE_KEYSTORE")?.trim().orEmpty()
val releaseStorePassword = System.getenv("G2RAY_RELEASE_STORE_PASSWORD")?.trim().orEmpty()
val releaseKeyAlias = System.getenv("G2RAY_RELEASE_KEY_ALIAS")?.trim().orEmpty()
val releaseKeyPassword = System.getenv("G2RAY_RELEASE_KEY_PASSWORD")?.trim().orEmpty()
val releaseSigningConfigured = releaseKeystorePath.isNotEmpty() &&
    releaseStorePassword.isNotEmpty() &&
    releaseKeyAlias.isNotEmpty() &&
    releaseKeyPassword.isNotEmpty() &&
    file(releaseKeystorePath).isFile

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02X".format(it) }
}

fun verifyLibXrayBundle(required: Boolean) {
    val aarExists = libXrayAar.isFile
    val metadataExists = libXrayPropertiesFile.isFile
    if (!aarExists) {
        check(!required) { "This build requires app/libs/libXray.aar and its metadata" }
        return
    }
    check(metadataExists) {
        "app/libs/libXray.aar requires app/libs/libXray.properties"
    }

    val metadata = Properties().apply { libXrayPropertiesFile.inputStream().use(::load) }
    val expectedHash = metadata.getProperty("libxray.sha256")?.trim()?.uppercase()
    check(!expectedHash.isNullOrBlank()) { "libXray metadata is missing libxray.sha256" }
    check(sha256(libXrayAar) == expectedHash) {
        "app/libs/libXray.aar does not match the pinned SHA-256; rebuild it with scripts/build-libxray-aar.ps1"
    }
    check(metadata.getProperty("libxray.ref") == "80263da83e96b2972455b0a94b13ee1a10e51391") {
        "libXray metadata has an unapproved libXray source revision"
    }
    check(metadata.getProperty("libxray.go") == "go1.26.10") {
        "libXray metadata has an unapproved Go toolchain revision"
    }
    check(metadata.getProperty("libxray.xray.ref") == "5ca6f4b7d4dc20a881d4330e498892697627ec0c") {
        "libXray metadata has an unapproved Xray source revision"
    }
    check(!metadata.getProperty("libxray.xray.ref").isNullOrBlank()) {
        "libXray metadata is missing libxray.xray.ref"
    }
    val expectedObserverPatchHash = metadata.getProperty("libxray.xray.observer.patch.sha256")?.trim()?.uppercase()
    check(!expectedObserverPatchHash.isNullOrBlank()) {
        "libXray metadata is missing libxray.xray.observer.patch.sha256"
    }
    check(sha256(libXrayObserverPatch) == expectedObserverPatchHash) {
        "The bundled libXray AAR was not built with the current Xray observer patch"
    }
    val expectedMeasurePatchHash = metadata.getProperty("libxray.measure.patch.sha256")?.trim()?.uppercase()
    check(!expectedMeasurePatchHash.isNullOrBlank()) {
        "libXray metadata is missing libxray.measure.patch.sha256"
    }
    check(sha256(libXrayMeasurePatch) == expectedMeasurePatchHash) {
        "The bundled libXray AAR was not built with the current measurement-status patch"
    }
}

if (releaseRequested) {
    verifyLibXrayBundle(required = true)
    check(releaseSigningConfigured) {
        "Release signing is not configured. Set G2RAY_RELEASE_KEYSTORE, " +
            "G2RAY_RELEASE_STORE_PASSWORD, G2RAY_RELEASE_KEY_ALIAS, and " +
            "G2RAY_RELEASE_KEY_PASSWORD before publishing an Android release."
    }
} else {
    verifyLibXrayBundle(required = false)
}

android {
  namespace = "com.g2ray.client"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  signingConfigs {
    if (releaseSigningConfigured) {
      create("release") {
        storeFile = file(releaseKeystorePath)
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  defaultConfig {
    applicationId = "com.g2ray.client"
    minSdk = 24
    targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
  }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.zxing.embedded)
    implementation(libs.zxing.core)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.converter.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation(libs.kotlinx.coroutines.android)
    if (libXrayAar.exists()) {
        implementation(files(libXrayAar))
    }

    testImplementation(libs.junit)
    testImplementation(libs.androidx.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

val enabledRobolectricSdks = providers.gradleProperty("robolectricSdks")
val configFixtureDirectory = providers.systemProperty("g2ray.configFixtureDir").orNull?.let { file(it) }
tasks.withType<Test>().configureEach {
    // Optional local SDK selection; CI exercises every declared SDK by default.
    enabledRobolectricSdks.orNull?.let { systemProperty("robolectric.enabledSdks", it) }
    if (name == "testDebugUnitTest") {
        configFixtureDirectory?.let { directory ->
            // Cached JUnit results must also restore the pinned-core validator's fixtures.
            systemProperty("g2ray.configFixtureDir", directory.absolutePath)
            outputs.dir(directory).withPropertyName("vpnConfigFixtures")
        }
    }
}

val verifyLibXrayBuildContract = tasks.register<VerifyLibXrayBuildContractTask>("verifyLibXrayBuildContract") {
    group = "verification"
    description = "Verifies that the production libXray builder uses a pinned, attested toolchain."
    buildScript.set(libXrayBuildScript)
    controllerPatch.set(libXrayControllerPatch)
    observerPatch.set(libXrayObserverPatch)
    measurePatch.set(libXrayMeasurePatch)
}

val verifyBundledCore = tasks.register<VerifyLibXrayBundleTask>("verifyBundledCore") {
    group = "verification"
    description = "Requires a bundled libXray AAR whose SHA-256 matches its pinned metadata."
    aarFile.set(libXrayAar)
    metadataFile.set(libXrayPropertiesFile)
    controllerPatch.set(libXrayControllerPatch)
    observerPatch.set(libXrayObserverPatch)
    measurePatch.set(libXrayMeasurePatch)
    required.set(true)
}

val verifyHevBundle = tasks.register<VerifyHevBundleTask>("verifyHevBundle") {
    group = "verification"
    description = "Verifies every bundled HEV ABI against pinned build metadata."
    jniLibsDirectory.set(hevJniLibsDirectory)
    metadataFile.set(hevMetadataFile)
}

val verifyReleasePersistenceSchema = tasks.register<VerifyPersistedJsonSchemaTask>("verifyReleasePersistenceSchema") {
    group = "verification"
    description = "Checks that R8 preserves the reflection-based saved account/profile JSON schema."
}

androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val r8Mapping = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
    verifyReleasePersistenceSchema.configure {
        mappingFile.set(r8Mapping)
        seedsFile.set(layout.file(r8Mapping.map { it.asFile.resolveSibling("seeds.txt") }))
        usageFile.set(layout.file(r8Mapping.map { it.asFile.resolveSibling("usage.txt") }))
    }
}

tasks.register("assembleFunctionalDebug") {
    group = "build"
    description = "Builds a debug APK only after verifying that the functional VPN core is bundled."
    dependsOn(verifyBundledCore, verifyHevBundle, verifyLibXrayBuildContract, "assembleDebug")
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    dependsOn(verifyBundledCore, verifyHevBundle, verifyLibXrayBuildContract)
}

tasks.matching { it.name == "packageDebug" }.configureEach {
    dependsOn(verifyBundledCore, verifyHevBundle, verifyLibXrayBuildContract)
}

tasks.named("check") {
    dependsOn(verifyLibXrayBuildContract, verifyHevBundle)
}

tasks.matching {
    it.name in setOf("assembleRelease", "bundleRelease", "packageRelease", "signReleaseBundle")
}.configureEach {
    dependsOn(verifyBundledCore, verifyHevBundle, verifyLibXrayBuildContract, verifyReleasePersistenceSchema)
}
