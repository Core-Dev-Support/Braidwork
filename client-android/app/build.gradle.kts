plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Версия берётся из тега git, а не пишется руками.
//
// Причина: зашитое значение разошлось с релизом. Приложение показывало
// «0.1.0» при релизе v0.7.0, и пользователь, увидевший в настройках одно, а в
// GitHub другое, перестал бы доверять обеим цифрам. Тег — единственное место,
// где версия записывается по-настоящему, и всё остальное обязано его читать.
//
// Тег читается только если сборка идёт из клона git. В CI клон есть всегда, и
// теги там есть тоже; локально без тегов сборка не должна падать — отсюда
// запасной вариант.
val версияИзТега: String? = runCatching {
    providers.exec {
        // Без `--abbrev=0` на сборке с ветки получается «0.7.0-12-gabc1d2»:
        // видно, что код новее последнего тега. С `--abbrev=0` было бы просто
        // «0.7.0», и сборка с невыпущенными изменениями выдавала бы себя за
        // релиз — ровно та подмена, от которой мы уходим.
        commandLine("git", "describe", "--tags", "--dirty")
        workingDir(rootProject.projectDir.parentFile)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().removePrefix("v").takeIf { it.isNotEmpty() }
}.getOrNull()

// `versionCode` растёт вместе с тегом: Android отказывается ставить сборку с
// меньшим кодом, и «откат» на предыдущую версию перестал бы работать. Плюс
// сам код — то, чем система различает сборки, поэтому он обязан быть
// упорядоченным, а не случайным.
val версия: String = версияИзТега ?: run {
    // Без тега показывается честное «неизвестно», а не выдуманная цифра:
    // придуманная версия в настройках сбила бы с толку точно так же, как
    // расхождение с релизом.
    logger.warn(
        "Тег git не найден: versionName будет '0.0.0-локальная'. " +
            "Для релиза соберите по тегу: git tag vX.Y.Z",
    )
    "0.0.0-локальная"
}

val кодВерсии: Int = версия.split("-").first()
    .split(".")
    .map { it.toIntOrNull() ?: 0 }
    .let { части ->
        val основа = части.getOrElse(0) { 0 }
        val дополнительное = части.getOrElse(1) { 0 }
        val исправление = части.getOrElse(2) { 0 }
        // ×100 и ×10000 держат порядок: 0.7.1 → 701, 0.10.0 → 1000.
        основа * 10000 + дополнительное * 100 + исправление
    }
    .coerceAtLeast(1)

android {
    namespace = "com.naua_braidwork.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.naua_braidwork.app"
        minSdk = 23
        targetSdk = 35
        versionCode = кодВерсии
        versionName = версия

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