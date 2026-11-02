package com.widglet.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "widglet")

val WidgletJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

/** Settings and per-widget configs, stored privately with DataStore as JSON. */
class Repo private constructor(private val context: Context) {
    private val ds get() = context.store

    private fun cfgKey(id: Int) = stringPreferencesKey("cfg_$id")
    private fun stateKey(id: Int) = stringPreferencesKey("st_$id")
    private val appKey = stringPreferencesKey("app")
    private val presetsKey = stringPreferencesKey("presets")
    private val statsKey = stringPreferencesKey("stats")

    private inline fun <reified T> decode(s: String?): T? =
        s?.let { runCatching { WidgletJson.decodeFromString<T>(it) }.getOrNull() }

    // ---- widget configs ----
    val configs: Flow<Map<Int, WidgetConfig>> = ds.data.map { prefs ->
        prefs.asMap().mapNotNull { (k, v) ->
            if (!k.name.startsWith("cfg_")) return@mapNotNull null
            val id = k.name.removePrefix("cfg_").toIntOrNull() ?: return@mapNotNull null
            decode<WidgetConfig>(v as? String)?.let { id to it }
        }.toMap().toSortedMap()
    }

    fun configFlow(id: Int): Flow<WidgetConfig?> = ds.data.map { decode<WidgetConfig>(it[cfgKey(id)]) }
    suspend fun config(id: Int): WidgetConfig? = configFlow(id).first()
    suspend fun allConfigs(): Map<Int, WidgetConfig> = configs.first()

    suspend fun saveConfig(id: Int, cfg: WidgetConfig) {
        ds.edit { it[cfgKey(id)] = WidgletJson.encodeToString(WidgetConfig.serializer(), cfg) }
    }

    suspend fun deleteWidget(id: Int) {
        ds.edit { it.remove(cfgKey(id)); it.remove(stateKey(id)) }
    }

    // ---- widget runtime state ----
    suspend fun state(id: Int): WidgetState = decode<WidgetState>(ds.data.first()[stateKey(id)]) ?: WidgetState()
    suspend fun updateState(id: Int, f: (WidgetState) -> WidgetState): WidgetState {
        var out = WidgetState()
        ds.edit { p ->
            out = f(decode<WidgetState>(p[stateKey(id)]) ?: WidgetState())
            p[stateKey(id)] = WidgletJson.encodeToString(WidgetState.serializer(), out)
        }
        return out
    }

    // ---- app settings ----
    val app: Flow<AppSettings> = ds.data.map { decode<AppSettings>(it[appKey]) ?: AppSettings() }
    suspend fun appNow(): AppSettings = app.first()
    suspend fun updateApp(f: (AppSettings) -> AppSettings) {
        ds.edit { p -> p[appKey] = WidgletJson.encodeToString(AppSettings.serializer(), f(decode<AppSettings>(p[appKey]) ?: AppSettings())) }
    }

    // ---- style presets ----
    private val presetList = ListSerializer(StylePreset.serializer())
    val presets: Flow<List<StylePreset>> = ds.data.map { p ->
        p[presetsKey]?.let { runCatching { WidgletJson.decodeFromString(presetList, it) }.getOrNull() } ?: emptyList()
    }
    suspend fun savePreset(p: StylePreset) {
        ds.edit { prefs ->
            val cur = prefs[presetsKey]?.let { runCatching { WidgletJson.decodeFromString(presetList, it) }.getOrNull() } ?: emptyList()
            prefs[presetsKey] = WidgletJson.encodeToString(presetList, cur.filterNot { it.name == p.name } + p)
        }
    }
    suspend fun deletePreset(name: String) {
        ds.edit { prefs ->
            val cur = prefs[presetsKey]?.let { runCatching { WidgletJson.decodeFromString(presetList, it) }.getOrNull() } ?: emptyList()
            prefs[presetsKey] = WidgletJson.encodeToString(presetList, cur.filterNot { it.name == name })
        }
    }

    // ---- local-only stats ----
    val stats: Flow<Stats> = ds.data.map { decode<Stats>(it[statsKey]) ?: Stats() }
    suspend fun updateStats(f: (Stats) -> Stats) {
        ds.edit { p ->
            val cur = decode<Stats>(p[statsKey]) ?: Stats(since = System.currentTimeMillis())
            p[statsKey] = WidgletJson.encodeToString(Stats.serializer(), f(cur))
        }
    }
    suspend fun resetStats() = ds.edit { it.remove(statsKey) }

    companion object {
        @Volatile private var instance: Repo? = null
        fun get(context: Context): Repo =
            instance ?: synchronized(this) { instance ?: Repo(context.applicationContext).also { instance = it } }
    }
}
