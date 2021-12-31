plugins {
  id("com.android.application")
  id("kotlin-android")
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
  implementation("androidx.appcompat:appcompat:1.4.0")
  implementation("com.google.android.material:material:1.4.0")
  implementation("androidx.compose.ui:ui:1.0.5")
  implementation("androidx.compose.material:material:1.0.5")
  implementation("androidx.compose.ui:ui-tooling-preview:1.0.5")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.4.0")
  implementation("androidx.activity:activity-compose:1.4.0")
  implementation("androidx.constraintlayout:constraintlayout-compose:1.0.0-rc02")

  debugImplementation("androidx.compose.ui:ui-tooling:1.0.5")

  val kotest = "5.0.2"
  testImplementation("io.kotest:kotest-runner-junit5:$kotest")
  testImplementation("io.kotest:kotest-assertions-core:$kotest")
  testImplementation("io.kotest.extensions:kotest-extensions-robolectric:0.4.0")

  val robolectric = "4.6"
  testImplementation("org.robolectric:robolectric:$robolectric")

  val androidTest = "1.4.0"
  testImplementation("androidx.test:core-ktx:$androidTest")

}
