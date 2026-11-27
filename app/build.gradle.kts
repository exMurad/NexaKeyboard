plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.io.File
import java.util.Base64

android {
    namespace = "com.murad.nexakeyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.murad.nexakeyboard"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "2.4"
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
// mipmap folders when this script is evaluated — i.e. before ANY build task
// (including resource merging) runs, so @mipmap/ic_launcher always resolves.
// (The earlier approach — a decodeLauncherIcon task hooked into preBuild —
// left the timing to the task graph; build #5 still failed with the icon
// resource missing, so the decode now happens at configuration time.)
run {
    val b64File = file("icon.png.b64")
    if (b64File.exists()) {
        val bytes = Base64.getMimeDecoder()
            .decode(b64File.readText().replace("\\s".toRegex(), ""))
        val densities = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
        for (d in densities) {
            val dir = file("src/main/res/mipmap-$d")
            dir.mkdirs()
            File(dir, "ic_launcher.png").writeBytes(bytes)
        }
        println("launcher icon: decoded icon.png.b64 for ${densities.size} densities")
    } else {
        println("launcher icon: icon.png.b64 not found, skipping")
    }
}
