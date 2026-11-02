package com.widglet.app.data

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable

/**
 * Per-widget settings export/import as JSON. Writes ONLY to a document the user picked in the system
 * "Save as" dialog (ACTION_CREATE_DOCUMENT) — never into the gallery or any media collection.
 * Exported files contain settings and photo references only, never photos.
 */
object SettingsBackup {
    @Serializable
    data class Backup(val app: String = "Widglet", val version: Int = 1, val config: WidgetConfig)

    fun export(context: Context, target: Uri, cfg: WidgetConfig): Boolean = try {
        context.contentResolver.openOutputStream(target, "wt")?.use {
            it.write(WidgletJson.encodeToString(Backup.serializer(), Backup(config = cfg)).toByteArray())
        } != null
    } catch (_: Exception) {
        false
    }

    fun import(context: Context, source: Uri): WidgetConfig? = try {
        context.contentResolver.openInputStream(source)?.use { s ->
            val text = s.readBytes().decodeToString()
            runCatching { WidgletJson.decodeFromString(Backup.serializer(), text).config }.getOrNull()
                ?: WidgletJson.decodeFromString(WidgetConfig.serializer(), text)
        }
    } catch (_: Exception) {
        null
    }

    fun presetToText(p: StylePreset): String = WidgletJson.encodeToString(StylePreset.serializer(), p)
    fun presetFromText(t: String): StylePreset? = runCatching { WidgletJson.decodeFromString(StylePreset.serializer(), t.trim()) }.getOrNull()
}
