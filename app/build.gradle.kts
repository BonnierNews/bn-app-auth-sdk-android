import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The example app's client and test values come from the git-ignored .env.properties
// (copy .env.properties.sample), and fall back to dummy values without it.
val envProperties = Properties().apply {
    rootProject.file(".env.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun envValue(name: String, dummy: String): String = envProperties.getProperty(name) ?: dummy

val loginRedirectUrl = envValue("EXAMPLE_LOGIN_REDIRECT_URL", "custom.redirect.scheme://www.test.se/login")

android {
    namespace = "se.bonniernews.appAuthExample"
    compileSdk = 35

    defaultConfig {
        applicationId = "se.bonniernews.appAuthExample"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        manifestPlaceholders["appAuthRedirectScheme"] = loginRedirectUrl.substringBefore("://")
        buildConfigField("String", "EXAMPLE_ISSUER", "\"${envValue("EXAMPLE_ISSUER", "https://oidc-provider.com/")}\"")
        buildConfigField("String", "EXAMPLE_CLIENT_ID", "\"${envValue("EXAMPLE_CLIENT_ID", "client-id")}\"")
        buildConfigField("String", "EXAMPLE_LOGIN_REDIRECT_URL", "\"$loginRedirectUrl\"")
        buildConfigField(
            "String",
            "EXAMPLE_LOGOUT_REDIRECT_URL",
            "\"${envValue("EXAMPLE_LOGOUT_REDIRECT_URL", "custom.redirect.scheme://www.test.se/logout")}\""
        )
        buildConfigField(
            "String",
            "EXAMPLE_TRANSFER_TOKEN_RESOURCE",
            "\"${envValue("EXAMPLE_TRANSFER_TOKEN_RESOURCE", "https://www.example.com/")}\""
        )
        buildConfigField(
            "String",
            "EXAMPLE_TRANSFER_TOKEN_AUDIENCE",
            "\"${envValue("EXAMPLE_TRANSFER_TOKEN_AUDIENCE", "site-client-id")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_21.toString()
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":BNAppAuth_Android"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("com.google.code.gson:gson:2.12.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.03.00"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}