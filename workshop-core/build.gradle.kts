plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":steam-protocol"))
    implementation(platform("com.squareup.okhttp3:okhttp-bom:5.3.2"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp")
    implementation("com.squareup.okhttp3:logging-interceptor")
    implementation("com.squareup.okio:okio:3.9.1")
    implementation("org.tukaani:xz:1.10")
    compileOnly("com.github.luben:zstd-jni:1.5.6-9")
}

// 开发用：在桌面上跑一遍真实工坊下载，验证链路
tasks.register<JavaExec>("probe") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath + files(
        configurations.detachedConfiguration(
            dependencies.create("com.github.luben:zstd-jni:1.5.6-9"),
            dependencies.create("org.slf4j:slf4j-simple:1.7.36"),
        )
    )
    mainClass.set("top.apricityx.workshop.workshop.ProbeKt")
    providers.gradleProperty("id").orNull?.let { systemProperty("id", it) }
    standardOutput = System.out
}
