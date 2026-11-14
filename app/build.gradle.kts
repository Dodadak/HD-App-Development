import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Room 코드 생성
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.hdapp"
    compileSdk = 34
    // 차속(PERF_VEHICLE_SPEED)을 받는 Android Automotive Car API
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.hdapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // 카카오 QR 로그인 서버 주소 (server/kakao-qr). local.properties 의 kakaoQrServer=… 로 넣는다. 비어 있으면 시연용 로그인
        val local = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        buildConfigField("String", "KAKAO_QR_SERVER", "\"${local.getProperty("kakaoQrServer", "")}\"")
        // 로그인 기록을 볼 수 있는 관리자 휴대폰 번호 (쉼표로 구분). local.properties 의 adminPhones=…
        buildConfigField("String", "ADMIN_PHONES", "\"${local.getProperty("adminPhones", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 공모전 심사용: 별도 배포 키가 없어서 debug 키로 서명 (서명 안 하면 release APK 설치 불가)
            // 실제 배포 때는 signingConfigs 에 release 키를 따로 만들 것
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

    testOptions {
        // 단위 테스트에서 android.util.Log 호출이 에러 나지 않게
        unitTests.isReturnDefaultValues = true
        // Robolectric(가짜 안드로이드)으로 DB·화면 테스트를 에뮬레이터 없이 돌린다
        unitTests.isIncludeAndroidResources = true
    }

    composeOptions {
        // Kotlin 1.9.24 와 짝이 맞는 Compose 컴파일러
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

// Room 표 구조를 버전별로 남겨서, 나중에 구조를 바꿀 때 옮기는 방법(마이그레이션)을 확인할 수 있게
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Pleos Connect SDK — 버전은 document.pleos.ai 기준 (2026-10 확인)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 화면 (Jetpack Compose)
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    // 카카오 로그인 QR 그리기
    implementation("com.google.zxing:core:3.5.3")

    // 주행 기록 저장 (Room)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    // 단위 테스트에서 org.json 실제 구현 (안드로이드 것은 빈 껍데기)
    testImplementation("org.json:json:20231013")
    // DB·화면 테스트 (에뮬레이터 없이)
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
