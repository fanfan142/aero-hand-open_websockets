plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aerohand"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aerohand"
        minSdk = 26
        targetSdk = 34
        versionCode = (project.findProperty("CI_VERSION_CODE") as String?)?.toIntOrNull() ?: 4
        versionName = (project.findProperty("CI_VERSION_NAME") as String?) ?: "1.1.2"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // 优先使用仓库外配置文件，其次使用 CI 环境变量；都不存在时回退 debug 签名
            val propsFile = rootProject.file("keystore.properties")
            val props = java.util.Properties()
            val hasProps = propsFile.exists()
            if (hasProps) {
                propsFile.inputStream().use { props.load(it) }
            }
            val envStoreFile = System.getenv("ANDROID_SIGNING_STORE_FILE")
            val envStorePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
            val envKeyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
            val envKeyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")

            val storePath = if (hasProps) {
                props.getProperty("storeFile")
            } else {
                envStoreFile
            }
            val storePwd = if (hasProps) {
                props.getProperty("storePassword")
            } else {
                envStorePassword
            }
            val alias = if (hasProps) {
                props.getProperty("keyAlias")
            } else {
                envKeyAlias
            }
            val keyPwd = if (hasProps) {
                props.getProperty("keyPassword")
            } else {
                envKeyPassword
            }

            if (!storePath.isNullOrBlank() &&
                !storePwd.isNullOrBlank() &&
                !alias.isNullOrBlank() &&
                !keyPwd.isNullOrBlank()
            ) {
                storeFile = file(storePath)
                storePassword = storePwd
                keyAlias = alias
                keyPassword = keyPwd
            } else {
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")

    // CameraX for gesture capture
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // MediaPipe Tasks Vision - hand landmark detection
    implementation("com.google.mediapipe:tasks-vision:0.10.29")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
