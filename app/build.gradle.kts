import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9.x membawa dukungan Kotlin bawaan. Menambah
    // id("org.jetbrains.kotlin.android") di sini akan menggagalkan build.
    id("com.android.application")
}

android {
    namespace = "dev.ifan.viusub"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.ifan.viusub"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
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

    // zipalign dari build-tools adalah binary x86_64 dan tidak ada paketnya
    // di repo Termux. useLegacyPackaging melewati alignment .so uncompressed
    // (APK jadi lebih besar, posisi .so tidak optimal). Hapus blok ini kalau
    // zipalign native sudah tersedia.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // core-ktx 1.19.0 mewajibkan compileSdk 37, yang di sini berarti memakai
    // platform preview android-37.0. 1.17.0 masih cocok dengan compileSdk 36.
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.2.1")
}