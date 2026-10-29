plugins { id("com.android.application") }

android {
    namespace = "com.ampylink.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.ampylink.app"
        minSdk = 30          // Android 11+
        targetSdk = 34
        versionCode = 12
        versionName = "1.5.3"
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
