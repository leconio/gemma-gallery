import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
abstract class PrepareOpenSourceNotices : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val notices: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val folder = outputDirectory.dir("licenses").get().asFile
        folder.mkdirs()
        notices.forEach { source -> source.copyTo(folder.resolve(source.name), overwrite = true) }
    }
}
val prepareOpenSourceNotices by tasks.registering(PrepareOpenSourceNotices::class) {
    notices.from(rootProject.files("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md"))
    outputDirectory.set(layout.buildDirectory.dir("generated/openSourceAssets"))
}
android {
    namespace = "com.spawnlau.gemmapocket"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.spawnlau.gemmapocket"
        minSdk = 28
        targetSdk = 35
        versionCode = 8
        versionName = "0.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += if (providers.gradleProperty("gemma.emulator").orNull == "true") "x86_64" else "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { jniLibs.useLegacyPackaging = true }
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareOpenSourceNotices, PrepareOpenSourceNotices::outputDirectory)
}
dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.10.5")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
