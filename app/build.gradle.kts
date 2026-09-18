plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val relayAccessToken = localProperties.getProperty("relay.access.token").orEmpty()
val relayAccessTokenLiteral = relayAccessToken
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r", "\\r")
    .replace("\n", "\\n")

// 可选的精简 UI 构建：隐藏调试操作（例如测试调用）以获得更清爽的 UI 体验，
// 同时保留服务器地址和令牌字段供用户配置。
// 通过 -PrelaySimpleUi=true 启用；默认构建不变。
val relaySimpleUi = (project.findProperty("relaySimpleUi") as String?)?.trim()?.toBoolean() ?: false
// 可选的构建时默认 relay 地址。为空（默认值）表示普通
// 构建不会预填任何内容，因此不会把服务器地址固化进 APK。
val relayBaseUrl = localProperties.getProperty("relay.baseUrl").orEmpty()
val relayBaseUrlLiteral = relayBaseUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r", "\\r")
    .replace("\n", "\\n")


if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.nogirelay.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nogirelay.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "1.0.0"

        buildConfigField("String", "DEFAULT_RELAY_URL", "\"$relayBaseUrlLiteral\"")
        buildConfigField("String", "RELAY_ACCESS_TOKEN", "\"$relayAccessTokenLiteral\"")
        buildConfigField("boolean", "SIMPLE_UI", relaySimpleUi.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"

    applicationVariants.all {
        val variantName = name
        val apkFileName = if (relaySimpleUi) "app-simple-$variantName.apk" else "app-$variantName.apk"
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.outputFileName = apkFileName
        }
        assembleProvider.configure {
            doLast {
                val variantOutputDir = layout.buildDirectory.dir("outputs/apk/$variantName").get().asFile
                val archiveDir = layout.buildDirectory.dir("outputs/apk/archive").get().asFile
                archiveDir.mkdirs()

                // 备份当前生成的 APK 到 archive 目录，避免被另一次构建的 Gradle 清理机制删掉
                val currentApk = File(variantOutputDir, apkFileName)
                if (currentApk.isFile) {
                    currentApk.copyTo(File(archiveDir, apkFileName), overwrite = true)
                }

                // 将已归档的历史产物（如之前构建的标准版或精简版 APK）同步回 debug 目录，实现两个 APK 同时保留
                archiveDir.listFiles { file -> file.isFile && file.name.endsWith(".apk") }?.forEach { archivedApk ->
                    val target = File(variantOutputDir, archivedApk.name)
                    if (!target.exists()) {
                        archivedApk.copyTo(target, overwrite = false)
                    }
                }
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
