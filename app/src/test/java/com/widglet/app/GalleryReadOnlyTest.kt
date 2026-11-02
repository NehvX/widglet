package com.widglet.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mirrors the `checkGalleryReadOnly` Gradle task: the app source must never contain gallery write/delete APIs.
 * Patterns are assembled from pieces so this test file itself doesn't contain them verbatim.
 */
class GalleryReadOnlyTest {
    private val forbidden = listOf(
        Regex("(contentResolver|ContentResolver|resolver)\\s*\\.\\s*(" + listOf("del" + "ete", "upd" + "ate", "ins" + "ert", "bulkIns" + "ert", "applyBa" + "tch").joinToString("|") + ")\\s*\\("),
        Regex("create(Write|Delete|Trash|Favorite)" + "Request"),
        Regex("insert" + "Image"),
        Regex("WRITE_EXTERNAL" + "_STORAGE|MANAGE_EXTERNAL" + "_STORAGE|ACCESS_MEDIA" + "_LOCATION"),
        Regex("getExternalStorage" + "PublicDirectory|DIRECTORY_" + "DCIM|DIRECTORY_" + "PICTURES"),
        Regex("IS_" + "PENDING"),
    )

    @Test
    fun appSourcesNeverWriteToTheGallery() {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
        val hits = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml") }.forEach { f ->
            f.readLines().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
                if (forbidden.any { it.containsMatchIn(line) }) hits += "${f.name}:${i + 1}: $line"
            }
        }
        assertTrue("Gallery write/delete API found:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun outputStreamsOnlyInSettingsBackup() {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "SettingsBackup.kt" }
            .filter { f -> f.readLines().any { !it.trim().startsWith("*") && !it.trim().startsWith("//") && it.contains("openOutput" + "Stream") } }
            .map { it.name }.toList()
        assertTrue("Unexpected output streams in $offenders", offenders.isEmpty())
    }
}
