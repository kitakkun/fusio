// Android application module. AGP 9 ships built-in Kotlin support, so no
// separate `kotlin-android` plugin is required — re-applying it would
// double-bind KGP on the build classpath, where it's already loaded via
// kotlin-multiplatform on fusio's library modules.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

android {
    namespace = "com.kitakkun.fusio.demo"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.kitakkun.fusio.demo"
        // Mirrors demo:shared (Compose animation-core requires 23).
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(project(":demo:shared"))
    // ComponentActivity + setContent { App() } host. Pulls androidx.compose.ui
    // transitively, so demo:shared's compose.runtime / .foundation / .material3
    // exports cover the UI primitives MainActivity stages.
    implementation(libs.androidx.activity.compose)
}
