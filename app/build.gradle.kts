plugins {
  id("opensab.android.application")
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.hilt.android)
  alias(libs.plugins.ksp)
  alias(libs.plugins.kover)
}

android {
    namespace = "com.opensab.app"
    defaultConfig {
        applicationId = "com.opensab"
        versionCode = 1
        versionName = "1.0.0"
    }

    // The release key never lives in the tree: CI decodes it from a secret and passes its path and
    // passwords through the environment. Without them the release APK is built unsigned.
    val releaseKeystore = providers.environmentVariable("OPENSAB_KEYSTORE")
    signingConfigs {
        if (releaseKeystore.isPresent) {
            create("release") {
                storeFile = file(releaseKeystore.get())
                storePassword = providers.environmentVariable("OPENSAB_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("OPENSAB_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("OPENSAB_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // R8 stays off: a release is the same bytecode that was verified on the bike.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    lint {
      lintConfig = file("lint.xml")
    }
}

dependencies {
  implementation(project(":logging"))
  implementation(project(":logging:journal"))
  implementation(project(":sab:protocol"))
  implementation(project(":sab:session"))
  implementation(project(":domain"))
  implementation(project(":cluster"))

  kover(project(":logging"))
  kover(project(":logging:journal"))
  kover(project(":sab:protocol"))
  kover(project(":sab:session"))
  kover(project(":domain"))
  kover(project(":cluster"))

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core.splashscreen)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Dependency Injection (Hilt + KSP)
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)
  implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Logging & Utilities
  implementation(libs.timber)

  // Media
  implementation(libs.androidx.media)

  // Persistence
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.kotlinx.serialization.json)

  // Local unit testing: JUnit4, Coroutines Test, Turbine
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.turbine)
  testImplementation(testFixtures(project(":domain")))
  testImplementation(testFixtures(project(":sab:session")))
  testImplementation(testFixtures(project(":cluster")))

  // Instrumented tests: JUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
