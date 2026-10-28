import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val targetAbis = providers.gradleProperty("abis").orElse("arm64-v8a").get().split(",")
require(targetAbis.all { it in setOf("arm64-v8a", "x86_64") })
val signingFile = rootProject.file(".signing/release.properties")
val signingProperties = Properties().apply {
    if (signingFile.isFile) signingFile.inputStream().use { load(it) }
}
fun signingValue(environment: String, property: String): String? =
    providers.environmentVariable(environment).orNull ?: signingProperties.getProperty(property)
val releaseStore = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull?.let { rootProject.file(it) }
    ?: signingProperties.getProperty("storeFile")?.let { signingFile.parentFile.resolve(it) }
val releaseStorePassword = signingValue("ANDROID_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = releaseStore != null &&
    listOf(releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }
android {
    namespace = "com.yk.clash"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.yk.clash"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += targetAbis }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = releaseStore
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            storeType = "PKCS12"
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    // Older cargo-ndk runs copied dependency cdylibs into jniLibs. Neither is
    // dynamically required by libykcore; exclude any stale copies as well.
    packaging { jniLibs.excludes += setOf("**/libboring_noise*.so", "**/libtun_rs*.so") }
}
val verifyNative by tasks.registering {
    doLast {
        project.exec {
            commandLine(listOf("python3", rootProject.file("scripts/native-fingerprint.py").absolutePath, "--verify") + targetAbis)
        }
    }
}
val verifyBundledRules by tasks.registering {
    doLast { project.exec { commandLine("python3", rootProject.file("scripts/verify-bundled-rules.py").absolutePath) } }
}
tasks.named("preBuild").configure { dependsOn(verifyNative, verifyBundledRules) }
val verifyReleaseSigning by tasks.registering {
    doLast {
        check(hasReleaseSigning && releaseStore?.isFile == true) {
            "Release signing is missing. Configure .signing/release.properties or ANDROID_KEYSTORE_PATH, ANDROID_STORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseSigning) }

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
