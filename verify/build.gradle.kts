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
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)

  implementation(libraryLibs.androidx.browser)
  implementation(libraryLibs.androidx.appcompat)
  implementation(libraryLibs.appauth)
  implementation(libraryLibs.jwks.rsa)
  implementation(libraryLibs.java.jwt)
  implementation(libraryLibs.ktor.client.core)
  implementation(libraryLibs.ktor.client.android)
  implementation(libraryLibs.ktor.client.content.negotiation)
  implementation(libraryLibs.ktor.serialization.kotlinx.json)
  implementation(platform(libraryLibs.opentelemetry.bom))
  implementation(libraryLibs.opentelemetry.api)
  implementation(libraryLibs.opentelemetry.sdk)
  implementation(libraryLibs.opentelemetry.extension.kotlin)
  implementation(libraryLibs.java.uuid.generator)

  testImplementation(libraryLibs.kotlinx.coroutines.test)
}
