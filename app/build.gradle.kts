import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

// secrets.properties
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "com.pintrip.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.pintrip.app"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // kakao keys
        buildConfigField("String", "KAKAO_REST_API_KEY", "\"${secrets.getProperty("KAKAO_REST_API_KEY", "")}\"")
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"${secrets.getProperty("KAKAO_NATIVE_APP_KEY", "")}\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Retrofit
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.scalars)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.ai)
    implementation(libs.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
