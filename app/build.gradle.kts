import java.util.Properties
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localKeystoreProps = Properties().apply {
    val propFile = rootProject.file("keystore.properties")
    if (propFile.isFile) {
        propFile.inputStream().use { load(it) }
    }
}

val localProps = Properties().apply {
    val propFile = rootProject.file("local.properties")
    if (propFile.isFile) {
        propFile.inputStream().use { load(it) }
    }
}

val envProps = Properties().apply {
    val envFile = rootProject.file(".env")
    if (envFile.isFile) {
        envFile.inputStream().use { load(it) }
    }
}

val rawGeminiApiKey = (System.getenv("GEMINI_API_KEY")
    ?: localProps.getProperty("GEMINI_API_KEY")
    ?: envProps.getProperty("GEMINI_API_KEY")
    ?: "").trim()
val escapedGeminiApiKey = rawGeminiApiKey.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.hanson.mobileuse"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hanson.mobileuse"
        minSdk = 31
        targetSdk = 35
        versionCode = 6
        versionName = "6.2.0"

        buildConfigField("String", "GEMINI_API_KEY", "\"$escapedGeminiApiKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("HANSON_KEYSTORE_PATH")
                ?: localKeystoreProps.getProperty("storeFile")
            val ksPassword = System.getenv("HANSON_KEYSTORE_PASSWORD")
                ?: localKeystoreProps.getProperty("storePassword")
            val aliasName = System.getenv("HANSON_KEY_ALIAS")
                ?: localKeystoreProps.getProperty("keyAlias")
            val aliasPassword = System.getenv("HANSON_KEY_PASSWORD")
                ?: localKeystoreProps.getProperty("keyPassword")

            if (!keystorePath.isNullOrBlank() &&
                !ksPassword.isNullOrBlank() &&
                !aliasName.isNullOrBlank() &&
                !aliasPassword.isNullOrBlank()
            ) {
                val ksFile = file(keystorePath)
                if (ksFile.isFile) {
                    storeFile = ksFile
                    storePassword = ksPassword
                    keyAlias = aliasName
                    keyPassword = aliasPassword
                }
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Security invariant: public release APKs must NEVER embed local developer API keys.
            buildConfigField("String", "GEMINI_API_KEY", "\"\"")
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { testTask ->
            (testTask as? org.gradle.api.tasks.testing.Test)?.apply {
                maxParallelForks = 1
                forkEvery = 0
                maxHeapSize = "1024m"
                jvmArgs("-Dfile.encoding=UTF-8", "-XX:MaxMetaspaceSize=256m")
                systemProperty("file.encoding", "UTF-8")
            }
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Official Google On-Device AI Infrastructure (S25 AICore Gemini Nano NPU + MediaPipe Tasks GenAI)
    implementation("com.google.ai.edge.aicore:aicore:0.0.1-exp02")
    implementation("com.google.mediapipe:tasks-genai:0.10.35")

    // Optional BYO Cloud Firestore Sync (runtime FirebaseOptions, no bundled config)
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth") {
        version {
            strictly("23.2.1")
        }
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.forkOptions.memoryMaximumSize = "1024m"
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    forkEvery = 0
    maxHeapSize = "1024m"
    jvmArgs("-Dfile.encoding=UTF-8", "-XX:MaxMetaspaceSize=256m")
    systemProperty("file.encoding", "UTF-8")
    environment("LANG", "en_US.UTF-8")
    environment("LC_ALL", "en_US.UTF-8")
}
