plugins { id("com.android.application") }

android {
    namespace = "com.ampylink.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.ampylink.app"
        minSdk = 30          // Android 11+
        targetSdk = 34
        versionCode = 9
        versionName = "1.5.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
// No dependencies: pure Android SDK + OpenGL ES 3.
