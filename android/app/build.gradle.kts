import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "app.gridcraft.viewer"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.gridcraft.viewer"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // The GridCraft web build (scripts/fetch-gridcraft.sh) is served from assets; keep the .wasm compressed.
    androidResources { noCompress.clear() }
    // Release signing key lives OUTSIDE the repo, in ~/APK-Keys/gridcraft/ (never commit it).
    // Without it the release APK is left unsigned.
    val ksProps = Properties().apply {
        val f = file(System.getenv("GRIDCRAFT_KEYSTORE_PROPS") ?: "${System.getProperty("user.home")}/APK-Keys/gridcraft/keystore.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (ksProps.isNotEmpty()) create("release") {
            storeFile = file(ksProps.getProperty("storeFile")); storePassword = ksProps.getProperty("storePassword")
            keyAlias = ksProps.getProperty("keyAlias"); keyPassword = ksProps.getProperty("keyPassword")
        }
    }
    lint { checkReleaseBuilds = false }
    buildTypes {
        // Debug installs next to a release build (own id, own label).
        debug { applicationIdSuffix = ".debug" }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

// GridCraft's web build (pinned + checksummed, cached in build-out/) and our page around it
// (src/main/web/) are put into assets before every build, so edits to web/ are never stale.
val prepareGridCraft by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir.parentFile
    commandLine("bash", "scripts/fetch-gridcraft.sh")
    inputs.files("src/main/web/host.js", "src/main/web/host.template.html", "../../gridcraft.version")
    outputs.dir("src/main/assets/gridcraft")
}
tasks.named("preBuild") { dependsOn(prepareGridCraft) }
