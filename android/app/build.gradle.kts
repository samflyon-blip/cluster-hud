plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The HUD screen is the same index.html that the web version uses (repo root).
val webAssets = layout.buildDirectory.dir("webassets")
val copyWeb by tasks.registering(Copy::class) {
    from(rootProject.file("../index.html"))
    into(webAssets)
}
tasks.named("preBuild") { dependsOn(copyWeb) }

android {
    namespace = "com.clusterhud.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clusterhud.app"
        minSdk = 30
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.2." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Fixed signing key so each new build installs over the last one.
    signingConfigs {
        create("cluster") {
            storeFile = file("cluster.keystore")
            storePassword = "clusterhud"
            keyAlias = "cluster"
            keyPassword = "clusterhud"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("cluster") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("cluster")
        }
    }

    sourceSets["main"].assets.srcDir(webAssets)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
