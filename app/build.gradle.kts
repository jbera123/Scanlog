import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.scanlog"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.scanlog"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures { compose = true }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
        jniLibs.useLegacyPackaging = true
    }

    sourceSets["main"].jniLibs.srcDirs("libs")
}

// --- Auto-copy a timestamped, named APK to the top of scanlog_2 on every
// assembleDebug. Resolves the real project top even when building from a git
// worktree nested under .claude/worktrees/<name>. Output is gitignored. ---
val scanlogTopDir: File = run {
    val root = rootProject.rootDir
    if (root.path.replace('\\', '/').contains("/.claude/worktrees/")) {
        // <name> -> worktrees -> .claude -> scanlog_2
        root.parentFile.parentFile.parentFile
    } else root
}

tasks.register("copyDebugApkToTop") {
    doLast {
        val apk = layout.buildDirectory
            .file("outputs/apk/debug/app-debug.apk").get().asFile
        if (!apk.exists()) {
            logger.warn("copyDebugApkToTop: APK not found at $apk")
            return@doLast
        }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val ver = android.defaultConfig.versionName ?: "0"
        val dest = File(scanlogTopDir, "scanlog-v$ver-debug-$ts.apk")
        apk.copyTo(dest, overwrite = true)
        logger.lifecycle("Copied APK -> $dest")
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy("copyDebugApkToTop")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation(fileTree("libs") { include("*.jar") })

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")


}
