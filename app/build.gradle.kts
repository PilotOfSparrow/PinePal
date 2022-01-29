plugins {
  id("com.android.application")

  id("kotlin-android")
  kotlin("kapt")

  id("dagger.hilt.android.plugin")
}

android {
  compileSdk = 31

  defaultConfig {
    applicationId = "com.vengefulhedgehog.pinepal"
    minSdk = 26
    targetSdk = 31
    versionCode = 1
    versionName = "1.0"

    vectorDrawables {
      useSupportLibrary = true
    }
  }

  buildTypes {
    getByName("release") {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility(JavaVersion.VERSION_11)
    targetCompatibility(JavaVersion.VERSION_11)
  }
  kotlinOptions {
    jvmTarget = JavaVersion.VERSION_11.majorVersion
  }
  buildFeatures {
    compose = true
  }
  composeOptions {
    kotlinCompilerExtensionVersion = "1.0.5"
  }
  kapt {
    correctErrorTypes = true
  }
  packagingOptions {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
  testOptions {
    unitTests.isIncludeAndroidResources = true
    unitTests.all(Test::useJUnitPlatform)
  }
}

dependencies {

  implementation("androidx.core:core-ktx:1.7.0")
  implementation("androidx.appcompat:appcompat:1.4.1")
  implementation("com.google.android.material:material:1.5.0")
  implementation("androidx.compose.ui:ui:1.0.5")
  implementation("androidx.compose.material:material:1.0.5")
  implementation("androidx.compose.ui:ui-tooling-preview:1.0.5")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.4.0")
  implementation("androidx.activity:activity-compose:1.4.0")
  implementation("androidx.constraintlayout:constraintlayout-compose:1.0.0")

  val hilt = "2.39.1"
  implementation("com.google.dagger:hilt-android:$hilt")
  kapt("com.google.dagger:hilt-android-compiler:$hilt")

  debugImplementation("androidx.compose.ui:ui-tooling:1.0.5")

  val kotest = "5.0.2"
  testImplementation("io.kotest:kotest-runner-junit5:$kotest")
  testImplementation("io.kotest:kotest-assertions-core:$kotest")

  val androidTest = "1.4.0"
  testImplementation("androidx.test:core-ktx:$androidTest")

  val mockk = "1.12.1"
  testImplementation("io.mockk:mockk:$mockk")
  testImplementation("io.mockk:mockk-agent-jvm:$mockk")

}
