plugins {
    id("com.android.application")
}

android {
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        targetSdk = 37
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}
