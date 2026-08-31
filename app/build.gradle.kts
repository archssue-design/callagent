plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val runNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val ksPass = System.getenv("KS_PASS") ?: ""
android {
    namespace = "kr.wsarch.callagent"
    compileSdk = 34
    defaultConfig {
        applicationId = "kr.wsarch.callagent"
        minSdk = 26
        targetSdk = 34
        versionCode = runNo
        versionName = "1.$runNo"
    }
    signingConfigs {
        create("release") {
            storeFile = file("release.p12"); storePassword = ksPass
            keyAlias = "callagent"; keyPassword = ksPass
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("release") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
