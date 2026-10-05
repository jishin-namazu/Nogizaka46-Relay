plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

import java.util.Properties

apply(from = rootProject.file("gradle/haze-glass-patch.gradle.kts"))

// Device rendering checks use a separate sandbox; never let a test runner
// install/uninstall the user's main application or touch its local data.
val renderVerification = providers.gradleProperty("relayRenderVerification").orNull == "true"

tasks.configureEach {
    if (name.startsWith("connected") && name.endsWith("AndroidTest")) {
        doFirst {
            check(renderVerification) {
                "Device tests require -PrelayRenderVerification=true to protect the main app and its local data."
            }
        }
    }
}

// 1. 读取 local.properties（如果存在则作为兜底配置）
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

// 2. Token：优先读取环境变量 RELAY_ACCESS_TOKEN，其次支持 Gradle 属性 (-P)，最后回退到 local.properties
val relayAccessToken = System.getenv("RELAY_ACCESS_TOKEN")
    ?: (project.findProperty("relayAccessToken") as String?)
    ?: (project.findProperty("relay.access.token") as String?)
    ?: localProperties.getProperty("relay.access.token").orEmpty()
val relayAccessTokenLiteral = relayAccessToken
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r", "\\r")
    .replace("\n", "\\n")

// 3. 精简 UI 模式：优先读取环境变量 RELAY_SIMPLE_UI，其次支持 -PrelaySimpleUi=true
val relaySimpleUi = (System.getenv("RELAY_SIMPLE_UI")?.trim()?.toBoolean())
    ?: ((project.findProperty("relaySimpleUi") as String?)?.trim()?.toBoolean())
    ?: false

// 4. 服务器地址：优先读取环境变量 RELAY_BASE_URL，其次支持 Gradle 属性，最后回退到 local.properties
val relayBaseUrl = System.getenv("RELAY_BASE_URL")
    ?: (project.findProperty("relayBaseUrl") as String?)
    ?: (project.findProperty("relay.baseUrl") as String?)
    ?: localProperties.getProperty("relay.baseUrl").orEmpty()
val relayBaseUrlLiteral = relayBaseUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r", "\\r")
    .replace("\n", "\\n")

// 4b. 基线配置文件专用包的同步地址与令牌：只注入 com.nogirelay.app.profile，不进入 debug/release。
//     优先 RELAY_PROFILE_BASE_URL / RELAY_PROFILE_ACCESS_TOKEN，其次 -PrelayProfileBaseUrl / -PrelayProfileAccessToken，
//     再次 local.properties 的 relay.profile.baseUrl / relay.profile.access.token，最后沿用上面的 relay.* 配置。
fun buildConfigString(value: String) = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r", "\\r")
    .replace("\n", "\\n") + "\""
val relayProfileBaseUrl = System.getenv("RELAY_PROFILE_BASE_URL")
    ?: (project.findProperty("relayProfileBaseUrl") as String?)
    ?: localProperties.getProperty("relay.profile.baseUrl")
    ?: relayBaseUrl
val relayProfileAccessToken = System.getenv("RELAY_PROFILE_ACCESS_TOKEN")
    ?: (project.findProperty("relayProfileAccessToken") as String?)
    ?: localProperties.getProperty("relay.profile.access.token")
    ?: relayAccessToken

// 5. FCM 配置：若本地无 google-services.json 文件，但环境变量提供了 GOOGLE_SERVICES_JSON，则自动写入
val googleServicesFile = file("google-services.json")
val googleServicesEnv = System.getenv("GOOGLE_SERVICES_JSON")
if (!googleServicesFile.exists() && !googleServicesEnv.isNullOrBlank()) {
    googleServicesFile.writeText(googleServicesEnv.trim())
}

if (googleServicesFile.exists() && !renderVerification) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.nogirelay.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nogirelay.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 12
        versionName = "1.0.0"

        buildConfigField("String", "DEFAULT_RELAY_URL", "\"$relayBaseUrlLiteral\"")
        buildConfigField("String", "RELAY_ACCESS_TOKEN", "\"$relayAccessTokenLiteral\"")
        buildConfigField("boolean", "SIMPLE_UI", relaySimpleUi.toString())
        buildConfigField("boolean", "RENDER_VERIFICATION", renderVerification.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        getByName("debug") {
            val keystoreFile = file("debug.keystore")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
            }
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            if (renderVerification) applicationIdSuffix = ".rendercheck"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    base {
        archivesName.set(if (renderVerification) "app-rendercheck" else if (relaySimpleUi) "app-simple" else "app")
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

// The profile generator installs its own build of the app. Give those build
// types (added by the baseline profile plugin) their own application id, so
// generating a profile never replaces the installed app or touches its data.
androidComponents {
    onVariants { variant ->
        val buildType = variant.buildType.orEmpty()
        if (buildType.startsWith("nonMinified") || buildType.startsWith("benchmark")) {
            variant.applicationId.set("com.nogirelay.app.profile")
            // The profiling app starts empty; with a server it syncs real messages
            // and blogs, so the recorded journeys render real lists.
            variant.buildConfigFields?.put(
                "DEFAULT_RELAY_URL",
                com.android.build.api.variant.BuildConfigField("String", buildConfigString(relayProfileBaseUrl), null),
            )
            variant.buildConfigFields?.put(
                "RELAY_ACCESS_TOKEN",
                com.android.build.api.variant.BuildConfigField("String", buildConfigString(relayProfileAccessToken), null),
            )
        }
    }
}

// google-services.json has no client for the profiling package, and profiling
// does not need push; the app starts without Firebase in that build.
tasks.matching {
    it.name.endsWith("GoogleServices") && (it.name.contains("NonMinified") || it.name.contains("Benchmark"))
}.configureEach { enabled = false }

baselineProfile {
    // Profiles are generated on demand (see baselineprofile/), not on every release build.
    automaticGenerationDuringBuild = false
}

tasks.matching { it.name in setOf("assembleDebug", "assembleRelease") }.configureEach {
    doLast {
        val variantName = if (name.contains("Release")) "release" else "debug"
        val apkFileName = if (relaySimpleUi) "app-simple-$variantName.apk" else "app-$variantName.apk"
        val variantOutputDir = layout.buildDirectory.dir("outputs/apk/$variantName").get().asFile
        val archiveDir = layout.buildDirectory.dir("outputs/apk/archive").get().asFile
        archiveDir.mkdirs()

        // 备份当前生成的 APK 到 archive 目录，避免被另一次构建的 Gradle 清理机制删掉
        val currentApk = File(variantOutputDir, apkFileName)
        if (currentApk.isFile) {
            currentApk.copyTo(File(archiveDir, apkFileName), overwrite = true)
        }

        // 将已归档的历史产物（如之前构建的标准版或精简版 APK）同步回目录，实现两个 APK 同时保留
        archiveDir.listFiles { file -> file.isFile && file.name.endsWith(".apk") }?.forEach { archivedApk ->
            val target = File(variantOutputDir, archivedApk.name)
            if (!target.exists()) {
                archivedApk.copyTo(target, overwrite = false)
            }
        }
    }
}

dependencies {
    // Compose BOM 2026.09.00 = ui/foundation 1.12.1 + material3 1.4.0（Material 3 Expressive）。
    // 注意：Compose 1.12.x 与 Haze 2.0.0 都要求 compileSdk >= 37。
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // 语音播放：ExoPlayer + MediaSession（媒体通知、锁屏与耳机线控、拔出耳机自动暂停）
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    // 侧载 APK 也能安装基线配置文件（应用商店之外首次启动即可受益）
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-messaging")

    // 液态玻璃（Liquid Glass）：Haze 2.0.0
    // haze              背景内容采集 + 效果基础设施
    // haze-blur         稳定、方向一致的区域背景模糊
    // haze-glass        折射驱动的玻璃材质（折射/模糊/染色/菲涅尔/镜面高光/色散）
    implementation("dev.chrisbanes.haze:haze:2.0.0")
    implementation("dev.chrisbanes.haze:haze-blur:2.0.0")
    implementation("dev.chrisbanes.haze:haze-glass:2.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
