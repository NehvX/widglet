plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.widglet.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.widglet.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    androidResources {
        // Only ship the languages we translate (English + Hindi).
        localeFilters += listOf("en", "hi")
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")

    testImplementation("junit:junit:4.13.2")
}

// ---------------------------------------------------------------------------
// Gallery read-only guard.
// Widglet must NEVER modify the user's gallery. This task fails the build if any
// MediaStore / ContentResolver write, delete or public-storage API (or a write
// permission) shows up in the app sources. It runs before every build.
// ---------------------------------------------------------------------------
val checkGalleryReadOnly by tasks.registering {
    group = "verification"
    description = "Fails the build if gallery write/delete APIs appear in the source."
    val srcDir = layout.projectDirectory.dir("src/main")
    inputs.dir(srcDir)
    doLast {
        val rules = listOf(
            Regex("""(contentResolver|ContentResolver|resolver)\s*\.\s*(delete|update|insert|bulkInsert|applyBatch)\s*\(""") to "ContentResolver write/delete",
            Regex("""create(Write|Delete|Trash|Favorite)Request""") to "MediaStore write/delete request",
            Regex("""insertImage""") to "MediaStore.Images.Media.insertImage",
            Regex("""WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|ACCESS_MEDIA_LOCATION|MANAGE_MEDIA""") to "write/manage storage permission",
            Regex("""SCHEDULE_EXACT_ALARM|USE_EXACT_ALARM""") to "exact alarm permission (not needed)",
            Regex("""android\.permission\.INTERNET""") to "network permission",
            Regex("""getExternalStoragePublicDirectory|DIRECTORY_DCIM|DIRECTORY_PICTURES|DIRECTORY_SCREENSHOTS""") to "public storage folder",
            Regex("""IS_PENDING|setIncludePending""") to "MediaStore pending (write) API",
            Regex("""openFileDescriptor\([^)]*"(w|wa|wt|rw|rwt)"""") to "writable file descriptor",
            Regex("""openOutputStream""") to "output stream on a content Uri",
        )
        // The settings export is the one place allowed to write, and only to a
        // document the user picked in the system "Save as" dialog (never the gallery).
        val allowOutputStream = setOf("SettingsBackup.kt")
        val violations = mutableListOf<String>()
        srcDir.asFile.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
            .forEach { file ->
                file.readLines().forEachIndexed { i, raw ->
                    val line = raw.trim()
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
                    if (line.contains("tools:node=\"remove\"")) return@forEachIndexed
                    for ((re, why) in rules) {
                        if (!re.containsMatchIn(line)) continue
                        if (why.startsWith("output stream") && file.name in allowOutputStream) continue
                        violations += "${file.name}:${i + 1}: $why -> $line"
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException("Gallery read-only rule violated:\n" + violations.joinToString("\n"))
        }
        println("checkGalleryReadOnly: OK (no gallery write/delete APIs found)")
    }
}

// Also verify the final merged manifest (including library manifests) has no
// network or storage-write permission.
tasks.configureEach {
    if (name.startsWith("process") && name.endsWith("MainManifest")) {
        doLast {
            val forbidden = listOf(
                "android.permission.INTERNET",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.MANAGE_EXTERNAL_STORAGE",
                "android.permission.ACCESS_MEDIA_LOCATION",
                "android.permission.SCHEDULE_EXACT_ALARM",
                "android.permission.USE_EXACT_ALARM",
            )
            outputs.files.asFileTree.matching { include("**/AndroidManifest.xml") }.forEach { f ->
                val text = f.readText()
                val hits = forbidden.filter { p -> Regex("uses-permission[^>]*\"$p\"").containsMatchIn(text) }
                if (hits.isNotEmpty()) throw GradleException("Merged manifest ${f.path} contains forbidden permissions: $hits")
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(checkGalleryReadOnly) }
