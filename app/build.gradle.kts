plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.murad.nexakeyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.murad.nexakeyboard"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "2.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}

// Launcher icon: the PNG cannot be pushed to GitHub as a binary, so the
// icon is stored as Base64 text in app/icon.png.b64 and decoded into the
// mipmap folders at build time, before resources are merged.
tasks.register("decodeLauncherIcon") {
    doLast {
        val b64File = file("icon.png.b64")
        if (!b64File.exists()) {
            println("decodeLauncherIcon: icon.png.b64 not found, skipping")
            return@doLast
        }
        val bytes = java.util.Base64.getMimeDecoder()
            .decode(b64File.readText().replace("\\s".toRegex(), ""))
        val densities = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
        for (d in densities) {
            val dir = file("src/main/res/mipmap-$d")
            dir.mkdirs()
            java.io.File(dir, "ic_launcher.png").writeBytes(bytes)
        }
        println("decodeLauncherIcon: wrote ic_launcher.png for ${densities.size} densities")
    }
}

tasks.named("preBuild").configure {
    dependsOn("decodeLauncherIcon")
}
