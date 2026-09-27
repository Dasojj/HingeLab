plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.duohome.hingelab"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.duohome.hingelab"
        minSdk = 30
        targetSdk = 35
        versionCode = 215
        versionName = "2.0-beta.15"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST") }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("com.flyfishxu:kadb:2.1.4")
    implementation("com.flyfishxu:kadb-mdns:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
}



