import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "com.moodwatch.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moodwatch.app"
        minSdk = 33
        targetSdk = 36
        versionCode = 22
        versionName = "1.21"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("upload") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    // The app has no fragments. This check fires anyway on registerForActivityResult.
    lint {
        disable += "InvalidFragmentVersionForActivityResult"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    implementation("androidx.wear.watchfacepush:watchfacepush:1.0.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

/**
 * Runs the Watch Face Push validator on the built :moods-face APK and emits it, its validation
 * token, and a string resource holding the token, as the app's default watch face.
 */
abstract class BundleMoodsFaceTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val faceApkDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val validatorJar: RegularFileProperty

    @get:Input
    abstract val appPackageName: Property<String>

    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val resDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun bundle() {
        val apkDir = faceApkDir.get().asFile
        @Suppress("UNCHECKED_CAST")
        val metadata = JsonSlurper().parse(apkDir.resolve("output-metadata.json")) as Map<String, Any>
        val elements = metadata["elements"] as List<Map<String, Any>>
        val apk = apkDir.resolve(elements.single()["outputFile"] as String)

        val log = ByteArrayOutputStream()
        execOps.javaexec {
            classpath(validatorJar)
            mainClass.set("com.google.android.wearable.watchface.validator.cli.DwfValidation")
            args("--apk_path=${apk.absolutePath}", "--package_name=${appPackageName.get()}")
            standardOutput = log
            errorOutput = log
            isIgnoreExitValue = true
        }
        val output = log.toString()
        val token = Regex("generated token: (\\S+)").find(output)?.groupValues?.get(1)
        if (!output.contains("Validation is successful") || token == null) {
            throw GradleException("Watch Face Push validation failed for ${apk.name}:\n$output")
        }
        logger.lifecycle("Watch Face Push validation passed for ${apk.name}")

        val assets = assetsDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        apk.copyTo(assets.resolve("default_watchface.apk"))
        assets.resolve("default_watchface_token.txt").writeText(token)

        val values = resDir.get().asFile.apply { deleteRecursively() }.resolve("values")
        values.mkdirs()
        values.resolve("default_wf_token.xml").writeText(
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<resources>
            |    <string name="default_wf_token" translatable="false">$token</string>
            |</resources>
            |""".trimMargin()
        )
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val bundleFace = tasks.register<BundleMoodsFaceTask>("bundleMoodsFace$name") {
            dependsOn(":moods-face:assemble$name")
            faceApkDir.set(rootProject.layout.projectDirectory.dir("moods-face/build/outputs/apk/${variant.name}"))
            validatorJar.set(rootProject.layout.projectDirectory.file("tools/validator-push-cli-1.1.0-alpha01.jar"))
            appPackageName.set(variant.applicationId)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundleFace, BundleMoodsFaceTask::assetsDir)
        variant.sources.res?.addGeneratedSourceDirectory(bundleFace, BundleMoodsFaceTask::resDir)
    }
}
