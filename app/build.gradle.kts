import java.io.File

plugins {
    id("com.android.application")
}

val dotEnv: Map<String, String> = run {
    val envFile = rootProject.file(".env")
    if (!envFile.isFile) {
        emptyMap()
    } else {
        envFile.readLines(Charsets.UTF_8)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                val key = line.substring(0, idx).trim()
                if (key.isEmpty()) return@mapNotNull null
                var value = line.substring(idx + 1).trim()
                if ((value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) ||
                    (value.startsWith("'") && value.endsWith("'") && value.length >= 2)
                ) {
                    value = value.substring(1, value.length - 1)
                }
                key to value
            }
            .toMap()
    }
}

fun signingProp(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: dotEnv[name]?.takeIf { it.isNotBlank() }

val signingStoreFilePath = signingProp("SIGNING_STORE_FILE")
val signingStorePassword = signingProp("SIGNING_STORE_PASSWORD")
val signingKeyAlias = signingProp("SIGNING_KEY_ALIAS")
val signingKeyPassword = signingProp("SIGNING_KEY_PASSWORD")
val signingStoreFile: File? = signingStoreFilePath?.let { path ->
    File(path).let { if (it.isAbsolute) it else rootProject.file(path) }
}
val hasReleaseSigning = signingStoreFile != null &&
    signingStoreFile.isFile &&
    !signingStorePassword.isNullOrBlank() &&
    !signingKeyAlias.isNullOrBlank() &&
    !signingKeyPassword.isNullOrBlank()

android {
    namespace = "io.github.colorosfontvf"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.colorosfontvf"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = signingStoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("androidx.annotation:annotation:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
