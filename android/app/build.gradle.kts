plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.dealsmasr.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dealsmasr.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "4.1.4"
        val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?).orEmpty()
        buildConfigField("String", "DEFAULT_API_URL", "\"$apiBaseUrl\"")
        // Supabase project URL + public anon/publishable key (not secret; data is protected by RLS).
        // Supplied via -PsupabaseUrl / -PsupabaseAnonKey or env SUPABASE_URL / SUPABASE_ANON_KEY — never committed.
        fun prop(name: String, env: String) = ((project.findProperty(name) as String?) ?: System.getenv(env)).orEmpty().trim()
        buildConfigField("String", "SUPABASE_URL", "\"${prop("supabaseUrl", "SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${prop("supabaseAnonKey", "SUPABASE_ANON_KEY")}\"")
        // Firebase (push). Public client identifiers, injected by CI from the Firebase project; empty = push disabled.
        buildConfigField("String", "FIREBASE_API_KEY", "\"${prop("firebaseApiKey", "FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${prop("firebaseAppId", "FIREBASE_APP_ID")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${prop("firebaseProjectId", "FIREBASE_PROJECT_ID")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${prop("firebaseSenderId", "FIREBASE_SENDER_ID")}\"")
    }

    // Fixed debug key (not a secret) so every CI build can update the previously installed app.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            manifestPlaceholders["cleartext"] = "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["cleartext"] = "false"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.security.crypto)
    implementation(libs.firebase.messaging)
    implementation(libs.mlkit.translate)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
