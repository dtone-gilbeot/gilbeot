import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releasePropertiesFile = file(
    System.getProperty("user.home") + "/.gilbeot/keystore.properties"
)
val releaseProperties = Properties()
val releasePropertyNames = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigningAvailable = if (releasePropertiesFile.isFile) {
    releasePropertiesFile.inputStream().use(releaseProperties::load)
    releasePropertyNames.all { !releaseProperties.getProperty(it).isNullOrBlank() }
} else {
    false
}

if (!releaseSigningAvailable) {
    logger.warn(
        "길벗 release signing 설정이 없어 unsigned APK를 만듭니다: $releasePropertiesFile"
    )
}

android {
    namespace = "ai.gilbeot.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.gilbeot.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    useLibrary("android.car")

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(releaseProperties.getProperty("storeFile"))
                storePassword = releaseProperties.getProperty("storePassword")
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = releaseProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    testImplementation("junit:junit:4.13.2")
}
