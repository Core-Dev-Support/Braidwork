plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.naua_braidwork.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.naua_braidwork.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Ядро собрано под четыре архитектуры. Универсальный APK весит больше,
        // поэтому для релиза отдаём раздельные через App Bundle, а вручную
        // ставим универсальный.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // Отладочная сборка подключается к ядру напрямую, минуя JNI-прокладку,
            // чтобы падать на том же месте.
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // Ядро уже сжато, второй раз сжимать бессмысленно и только тратит время.
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // Внешней нативной сборки больше нет: мост к JVM живёт внутри ядра на Rust.
    // Вторая библиотека с неопределёнными символами ломалась при загрузке на
    // устройстве, и CMake больше не нужен.
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    // Нужен для WindowCompat: системные цвета задаются вместе с темой,
    // иначе статус-бар остаётся светлым на тёмном экране.
    implementation(libs.androidx.core.ktx)

    debugImplementation(libs.androidx.compose.ui.tooling)
}