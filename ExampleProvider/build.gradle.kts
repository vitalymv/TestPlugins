dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// Use an integer for version numbers
version = 1

cloudstream {
  language = "uk"
    version = 2
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
