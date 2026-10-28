import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Through providers so the configuration cache notices edits to local.properties.
val localProps = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull
        ?.let { load(it.reader()) }
}
fun local(key: String, default: String) = localProps.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: default
// The repo's config.json is the one place for URLs (docs/CONFIG.md); local.properties only holds this PC's paths and secrets.
@Suppress("UNCHECKED_CAST")
val config = groovy.json.JsonSlurper().parseText(
    providers.fileContents(rootProject.layout.projectDirectory.file("config.json")).asText.get()
) as Map<String, Any>
// The dev app's own key: local.properties on this PC, CONTROLLED_DEV_STORE_* in CI (from SSM /controlled-dev/dev-keystore).
fun devSigning(key: String, env: String, default: String) = local("controlled.$key", System.getenv(env)?.takeIf { it.isNotEmpty() } ?: default)

android {
    namespace = "dev.unsubscribed.controlled"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.unsubscribed.controlled"
        minSdk = 26
        targetSdk = 37
        versionCode = 9
        versionName = "1.8"
        // Play wants the privacy policy reachable from inside the app too (docs/play-listing.md).
        buildConfigField("String", "PRIVACY_URL", "\"${config.getValue("privacyUrl")}\"")
        // AGPL: the app links to its own source.
        buildConfigField("String", "SOURCE_URL", "\"${config.getValue("sourceUrl")}\"")
    }

    // prod ships through Google Play; dev installs next to it from the dev site and updates itself (docs/CONFIG.md).
    flavorDimensions += "env"
    productFlavors {
        for (env in listOf("prod", "dev")) create(env) {
            dimension = "env"
            @Suppress("UNCHECKED_CAST")
            val urls = config.getValue(env) as Map<String, String>
            // The public page: download and latest.json.
            buildConfigField("String", "HOME_URL", "\"${urls.getValue("homeUrl").trimEnd('/')}\"")
            // Dev logs connection timings and TV answers (adb logcat -s Controlled); prod never does.
            buildConfigField("boolean", "LOG", "${env == "dev"}")
        }
        getByName("dev") {
            applicationIdSuffix = ".dev"
            // Dev builds are numbered by commit count (release.sh dev), never by the prod versionCode above.
            providers.gradleProperty("versionCodeOverride").orNull?.let { versionCode = it.toInt() }
            providers.gradleProperty("versionNameOverride").orNull?.let { versionName = it }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = localProps.getProperty("controlled.uploadStoreFile")?.trim()?.takeIf { it.isNotEmpty() }?.let(::file)
            storePassword = local("controlled.uploadStorePassword", "")
            keyAlias = local("controlled.uploadKeyAlias", "")
            keyPassword = local("controlled.uploadKeyPassword", "")
        }
        create("dev") {
            // A missing path, not null: with no store file AGP quietly signs with the default debug key.
            storeFile = file(devSigning("devStoreFile", "CONTROLLED_DEV_STORE_FILE", "no-dev-keystore-set-controlled.devStoreFile"))
            storePassword = devSigning("devStorePassword", "CONTROLLED_DEV_STORE_PASSWORD", "android")
            keyAlias = devSigning("devKeyAlias", "CONTROLLED_DEV_KEY_ALIAS", "controlled-dev")
            keyPassword = devSigning("devKeyPassword", "CONTROLLED_DEV_KEY_PASSWORD", "android")
        }
    }

    buildTypes {
        // debug is for Android Studio and the emulator only: phones get R8-optimized, non-debuggable builds.
        debug { buildConfigField("boolean", "SELF_UPDATE", "false") }
        release {
            // Play forbids self-updates: only the sideloaded dev build checks latest.json.
            buildConfigField("boolean", "SELF_UPDATE", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the upload key in local.properties the release build is just left unsigned.
            signingConfigs["release"].takeIf { it.storeFile != null }?.let { signingConfig = it }
        }
        // What release.sh publishes on the dev page: release's optimizations, the dev key, and the update check.
        create("sideload") {
            initWith(getByName("release"))
            buildConfigField("boolean", "SELF_UPDATE", "true")
            signingConfig = signingConfigs["dev"]
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

androidComponents {
    beforeVariants(selector().withFlavor("env" to "dev").withBuildType("release")) { it.enable = false } // no Play build for dev
    beforeVariants(selector().withFlavor("env" to "prod").withBuildType("sideload")) { it.enable = false } // prod is Play only
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    // Installs Compose's baseline profiles on sideloaded installs, which no store compiles ahead of time.
    implementation(libs.androidx.profileinstaller)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
