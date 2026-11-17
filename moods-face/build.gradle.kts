import java.util.Properties

plugins {
    id("com.android.application")
}

// Pushed by the Moods app with Watch Face Push, so the package must be
// <app package>.watchfacepush.<name>.
val facePackage = "com.moodwatch.app.watchfacepush.moods"

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = facePackage
    compileSdk = 36

    defaultConfig {
        applicationId = facePackage
        minSdk = 34
        targetSdk = 36
        versionCode = 19
        versionName = "1.18"
    }

    // Watch Face Push requires its own signing key, separate from the app's upload key.
    signingConfigs {
        if (keystoreProps.getProperty("faceStoreFile") != null) {
            create("face") {
                storeFile = rootProject.file(keystoreProps.getProperty("faceStoreFile"))
                storePassword = keystoreProps.getProperty("faceStorePassword")
                keyAlias = keystoreProps.getProperty("faceKeyAlias")
                keyPassword = keystoreProps.getProperty("faceKeyPassword")
            }
        }
    }

    // Watch Face Format packages must contain no code, so let R8 drop the generated R classes.
    buildTypes {
        debug {
            isMinifyEnabled = true
        }
        release {
            isMinifyEnabled = true
            vcsInfo.include = false
            signingConfig = signingConfigs.findByName("face") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = false
    }

    // Watch Face Push accepts only the manifest, resources and META-INF in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// @font/mood_font is Brice when its commercially licensed file is in the git-ignored
// private-fonts/ folder, and Fredoka (SIL OFL) otherwise.
val privateFont = rootProject.file("private-fonts/Brice-SemiBoldSemiExpanded.otf")
val moodFontRes = layout.buildDirectory.dir("generated/moodFont/res")
val syncMoodFont = tasks.register<Sync>("syncMoodFont") {
    from(if (privateFont.isFile) privateFont else rootProject.file("third_party/fredoka/Fredoka-Bold.ttf"))
    into(moodFontRes.map { it.dir("font") })
    rename { "mood_font." + it.substringAfterLast('.') }
}
android.sourceSets.getByName("main").res.srcDir(moodFontRes)
tasks.named("preBuild") { dependsOn(syncMoodFont) }
