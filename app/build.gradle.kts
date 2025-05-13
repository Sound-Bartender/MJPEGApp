
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "kr.goldenmine.mjpegapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.goldenmine.mjpegapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
//    packagingOptions {
//        resources {
//            // 확장자를 꼭 추가해야 압축/변형이 안 됩니다.
//            noCompress += setOf("tflite", "lite")
//        }
//    }
    // TensorFlow Lite 모델 파일 압축 방지
//    aaptOptions {
//        noCompress += "tflite"
//        noCompress += "lite"
//    }
    // TensorFlow Lite 모델 파일 압축 방지 (새로운 방식)
//    packagingOptions {
//        resources {
//            // 기존 noCompress 리스트에 확장자를 추가합니다.
//            // AGP 버전에 따라 Set을 직접 할당하거나, 리스트에 추가하는 방식이 사용됩니다.
//            // 가장 일반적인 방법은 기존 리스트에 추가하는 것입니다.
//            // 만약 resources.noCompress가 MutableList<String> 타입이라면 아래와 같이 사용합니다.
//            noCompress.add("tflite")
//            // 여러 확장자를 추가해야 한다면 여러 번 호출하거나 리스트로 추가합니다.
//            // noCompress.addAll(listOf("tflite", "lite"))
//        }
//    }
    androidResources {
        noCompress  += "tflite"
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }
//    aaptOptions {
//        noCompress += "tflite" // 모델 파일 확장자가 .tflite가 아니라면 해당 확장자로 변경
//    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    // https://mvnrepository.com/artifact/org.tensorflow/tensorflow-lite
    implementation("org.tensorflow:tensorflow-lite:2.15.0")
    // https://mvnrepository.com/artifact/org.tensorflow/tensorflow-lite-gpu
    implementation("org.tensorflow:tensorflow-lite-gpu:2.15.0")
    // https://mvnrepository.com/artifact/org.tensorflow/tensorflow-lite-gpu-delegate-plugin
//    implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.15.0")

    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.15.0")

    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

//    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.16.1") // ✅ 필수
    // AI 기능 관련 라이브러리

    implementation(libs.tasks.vision) // MediaPipe Vision Tasks 라이브러리
    implementation(libs.onnxruntime.android)

}