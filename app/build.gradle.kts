plugins {
    id("com.android.application")
}

android {
    namespace = "fr.siride.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.siride.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "V1"
    }

    // Keystore de debug FIXE (fichier app/debug.keystore versionné dans le dépôt),
    // pour que tous les builds (locaux et GitHub Actions) partagent la même signature.
    // Sans cela, Gradle génère une clé de debug aléatoire à chaque environnement de
    // build, ce qui provoque un échec d'installation ("signatures do not match" /
    // "App not installed") dès qu'une version précédente signée différemment est
    // déjà présente sur l'appareil.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // FileProvider (androidx.core) : nécessaire pour ouvrir le PDF du manuel
    // opérateur avec une visionneuse externe (content:// URI, pas de file://).
    implementation("androidx.core:core:1.13.1")
	implementation ("com.github.mik3y:usb-serial-for-android:3.11.0")
}
