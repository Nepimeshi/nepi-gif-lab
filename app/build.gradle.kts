import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.artifact.SingleArtifact
import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.io.File
import java.io.StringReader
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

abstract class RenameReleaseApkTask : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val inputApkDir: DirectoryProperty

  @get:OutputDirectory
  abstract val outputApkDir: DirectoryProperty

  @get:Internal
  abstract val transformationRequest: Property<ArtifactTransformationRequest<RenameReleaseApkTask>>

  @TaskAction
  fun taskAction() {
    transformationRequest.get().submit(this) { builtArtifact ->
      val version = builtArtifact.versionName?.takeIf { it.isNotBlank() }
        ?: error("Release build must define a non-blank versionName")
      val inputFile = File(builtArtifact.outputFile)
      val unsignedSuffix = if (inputFile.name.endsWith("-unsigned.apk")) "-unsigned" else ""
      val outputFile = outputApkDir.get().file("Nepi-GIF-Lab-v$version$unsignedSuffix.apk").asFile
      inputFile.copyTo(outputFile, overwrite = true)
      outputFile
    }
  }
}

val keystorePropertiesText = providers
  .fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
  .asText
  .orNull
val keystoreProperties = Properties().apply {
  if (!keystorePropertiesText.isNullOrBlank()) {
    load(StringReader(keystorePropertiesText))
  }
}

android {
  namespace = "com.nepimeshi.nepigiflab"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.nepimeshi.nepigiflab"
    minSdk = 24
    targetSdk = 36
    versionCode = 3
    versionName = "1.2.6"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = keystoreProperties.getProperty("storeFile")
        ?: providers.gradleProperty("KEYSTORE_PATH").orNull
        ?: providers.environmentVariable("KEYSTORE_PATH").orNull
      val releaseKeyAlias = keystoreProperties.getProperty("keyAlias")
        ?: providers.gradleProperty("KEY_ALIAS").orNull
        ?: providers.environmentVariable("KEY_ALIAS").orNull
        ?: "nepi-gif-lab"
      val releaseStorePassword = keystoreProperties.getProperty("storePassword")
        ?: providers.gradleProperty("STORE_PASSWORD").orNull
        ?: providers.environmentVariable("STORE_PASSWORD").orNull
      val releaseKeyPassword = keystoreProperties.getProperty("keyPassword")
        ?: providers.gradleProperty("KEY_PASSWORD").orNull
        ?: providers.environmentVariable("KEY_PASSWORD").orNull

      if (!keystorePath.isNullOrBlank()) {
        storeFile = file(keystorePath)
      }
      storePassword = releaseStorePassword
      keyAlias = releaseKeyAlias
      keyPassword = releaseKeyPassword
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      val releaseSigning = signingConfigs.getByName("release")
      if (releaseSigning.storeFile != null) {
        signingConfig = releaseSigning
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

androidComponents {
  onVariants(selector().withBuildType("release")) { variant ->
    val renameTask = tasks.register<RenameReleaseApkTask>(
      "rename${variant.name.replaceFirstChar { it.uppercase() }}Apk"
    )
    val request = variant.artifacts.use(renameTask)
      .wiredWithDirectories(
        RenameReleaseApkTask::inputApkDir,
        RenameReleaseApkTask::outputApkDir
      )
      .toTransformMany(SingleArtifact.APK)
    renameTask.configure {
      transformationRequest.set(request)
    }
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.coil.compose)
  implementation(libs.coil.gif)
  implementation(libs.squareup.gifencoder)
  // Genuine Google libwebp WebPAnimEncoder JNI bindings (MIT / BSD-3-Clause);
  // exclude okhttp because Nepi GIF Lab is strictly 100% offline and only passes local Bitmaps/Files.
  implementation(libs.webp.android) {
    exclude(group = "com.squareup.okhttp3", module = "okhttp")
  }
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // implementation(libs.firebase.firestore)
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  // implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}
