import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名口令不写进仓库：放在 keystore.properties（.gitignore 里），没有这个文件就出未签名的包
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) {
        keystorePropsFile.inputStream().use { stream -> load(stream) }
    }
}

android {
    namespace = "moe.ddlauncher"
    compileSdk = 36

    defaultConfig {
        // 与游戏同包名：mods 目录、存档路径与官方版完全一致，现有 mod 无需迁移
        applicationId = "com.darkestdungeon"
        minSdk = 26
        targetSdk = 36
        versionCode = 202201
        versionName = "2.2.1"
    }

    signingConfigs {
        if (keystorePropsFile.isFile) {
            create("local") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.isFile) signingConfig = signingConfigs.getByName("local")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui:1.10.3")
    implementation("androidx.compose.ui:ui-graphics:1.10.3")
    implementation("androidx.compose.foundation:foundation:1.10.3")
    implementation("androidx.compose.material3:material3:1.3.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.10.3")

    // 工坊下载（vendored: steam-protocol / workshop-core, Apache-2.0, Apricityx/WorkshopAndroidDownloader）
    implementation(project(":workshop-core"))
    implementation(platform("com.squareup.okhttp3:okhttp-bom:5.3.2"))
    implementation("com.squareup.okhttp3:okhttp")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Steam 的 chunk 有 VZstd（zstd）压缩的，workshop-core 里是 compileOnly 声明（桌面探针用），
    // App 侧必须自己带上 —— 而且只能取 @aar（带 Android 原生 .so），
    // 普通 jar 里的 linux/mac 原生库在 Android 上加载不了，会 NoClassDefFoundError: Zstd。
    implementation("com.github.luben:zstd-jni:1.5.6-9@aar")
}
