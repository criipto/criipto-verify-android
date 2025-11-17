plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  kotlin("plugin.serialization") version "2.2.20"
}

android {
  buildFeatures {
    buildConfig = true
  }

  namespace = "eu.idura.verify"
  compileSdk {
    version = release(36)
  }

  defaultConfig {
    minSdk = 29

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("consumer-rules.pro")
    val version = "0.0.1"
    buildConfigField("String", "VERSION", "\"$version\"")
    setVersion(version)
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.browser)
  implementation(libs.material)
  implementation(libs.appauth)
  implementation(libs.jwks.rsa)
  implementation(libs.java.jwt)

  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.android)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.kotlinx.serialization)
  implementation(libs.ktor.serialization.kotlinx.json)

  implementation(platform(libs.opentelemetry.bom))
  implementation(libs.opentelemetry.api)
  implementation(libs.opentelemetry.sdk)
  implementation(libs.opentelemetry.extension.kotlin)

  implementation(libs.java.uuid.generator)

  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
}
