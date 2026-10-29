import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}
room { schemaDirectory("$projectDir/schemas") }
// An isolated output directory avoids locks from another Android Studio build.
if (providers.gradleProperty("isolatedBuild").isPresent) {
    layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("artifacts/gradle-build"))
}
val serviceSettings = Properties().apply {
    rootProject.file("services.local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val signingSettings = Properties().apply {
    rootProject.file(".private/release.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun settingLiteral(key: String): String = "\"" + serviceSettings.getProperty(key, "").trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
android {
    namespace = "cn.musicpilgrimage.app"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "cn.musicpilgrimage.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 24
        versionName = "2.2.1"
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
        buildConfigField("String", "SUPABASE_URL", settingLiteral("supabase.url"))
        buildConfigField("String", "SUPABASE_ANON_KEY", settingLiteral("supabase.anonKey"))
        buildConfigField("String", "MAPTILER_KEY", settingLiteral("maptiler.key"))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true; prefab = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        if(signingSettings.isNotEmpty()) create("pilgrimageRelease") {
            storeFile = rootProject.file(signingSettings.getProperty("storeFile"))
            storePassword = signingSettings.getProperty("storePassword")
            keyAlias = signingSettings.getProperty("keyAlias")
            keyPassword = signingSettings.getProperty("keyPassword")
        }
    }
    buildTypes {
        if (providers.gradleProperty("tingzongReview").orNull == "true") {
            getByName("debug") { applicationIdSuffix = ".review"; versionNameSuffix = "-review" }
        }
        release {
        isMinifyEnabled = false
        if(signingSettings.isNotEmpty()) signingConfig = signingConfigs.getByName("pilgrimageRelease")
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    } }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}") }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("com.google.oboe:oboe:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation(platform("io.github.jan-tennert.supabase:bom:3.1.4"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.ktor:ktor-client-okhttp:3.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    // The existing Coil SVG runtime is also used directly for the independent globe print.
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.01"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
val prepareCatalog = tasks.register<Copy>("prepareCatalog") {
    from(rootProject.file("content/catalog.json"))
    into(layout.buildDirectory.dir("generated/catalogAssets"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/catalogAssets").get().asFile)
android.sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
tasks.named("preBuild").configure { dependsOn(prepareCatalog) }
