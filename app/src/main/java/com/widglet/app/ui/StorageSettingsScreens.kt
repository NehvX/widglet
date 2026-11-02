package com.widglet.app.ui

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.widglet.app.R
import com.widglet.app.cutout.CleanupWorker
import com.widglet.app.cutout.CutoutStore
import com.widglet.app.cutout.CutoutWorker
import com.widglet.app.cutout.Segmenter
import com.widglet.app.cutout.StorageMath
import com.widglet.app.data.AppSettings
import com.widglet.app.data.PhotoSelector
import com.widglet.app.data.Quality
import com.widglet.app.data.Repo
import com.widglet.app.data.Stats
import com.widglet.app.data.WidgetConfig
import com.widglet.app.widget.Scheduler
import com.widglet.app.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private fun dirSize(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0L

private data class Usage(val cutouts: Long, val frames: Long, val cache: Long, val other: Long, val free: Long) {
    val total get() = cutouts + frames + cache + other
}

private fun measure(ctx: Context): Usage {
    val cut = CutoutStore.get(ctx).sizeBytes()
    val frames = dirSize(File(ctx.filesDir, "frames"))
    val files = dirSize(ctx.filesDir)
    val data = ctx.filesDir.parentFile?.let { dirSize(File(it, "datastore")) + dirSize(File(it, "databases")) } ?: 0L
    return Usage(cut, frames, dirSize(ctx.cacheDir), (files - cut - frames).coerceAtLeast(0) + data, StatFs(ctx.filesDir.path).availableBytes)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(nav: Nav) {
    val ctx = LocalContext.current
    val repo = remember { Repo.get(ctx) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var refresh by remember { mutableIntStateOf(0) }
    var usage by remember { mutableStateOf<Usage?>(null) }
    val configs by repo.configs.collectAsState(initial = emptyMap())
    var perWidget by remember { mutableStateOf<Map<Int, Long>>(emptyMap()) }
    var counts by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(refresh, configs) {
        usage = withContext(Dispatchers.IO) { measure(ctx) }
        val store = CutoutStore.get(ctx)
        val res = withContext(Dispatchers.IO) {
            configs.mapValues { (id, c) -> PhotoSelector(ctx).resolve(c, repo.state(id).shuffleSeed).photos.map { it.uri } }
        }
        counts = res.mapValues { it.value.size }
        perWidget = withContext(Dispatchers.IO) { res.mapValues { (id, uris) -> store.bytesFor(uris, configs.getValue(id).quality) } }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.storage)) },
            navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val u = usage
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.space_used), style = MaterialTheme.typography.titleMedium)
                    if (u == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                        Text(StorageMath.human(u.total), style = MaterialTheme.typography.displaySmall)
                        Text(stringResource(R.string.usage_breakdown, StorageMath.human(u.cutouts), StorageMath.human(u.frames + u.cache), StorageMath.human(u.other)), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.free_space, StorageMath.human(u.free)), style = MaterialTheme.typography.bodySmall)
                    }
                    InfoText(stringResource(R.string.private_only))
                }
            }

            if (perWidget.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.per_widget), style = MaterialTheme.typography.titleMedium)
                    val max = (perWidget.values.maxOrNull() ?: 0L).coerceAtLeast(1L)
                    perWidget.forEach { (id, bytes) ->
                        val name = configs[id]?.name?.ifBlank { null } ?: stringResource(R.string.widget_n, id)
                        Text("$name · ${StorageMath.human(bytes)}", style = MaterialTheme.typography.bodyMedium)
                        Box(Modifier.fillMaxWidth().height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))) {
                            Box(Modifier.fillMaxWidth(bytes / max.toFloat()).height(12.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)))
                        }
                    }
                }
            }

            configs.filter { it.value.hasSources }.forEach { (id, c) ->
                EstimateCard(id, c, counts[id] ?: 0, usage?.free ?: Long.MAX_VALUE, onRefresh = { refresh++ })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { CleanupWorker.cleanUnused(ctx) }
                        toast(if (n < 0) ctx.getString(R.string.cleanup_skipped) else ctx.getString(R.string.cleaned_n, n))
                        refresh++
                    }
                }) { Text(stringResource(R.string.clean_unused)) }
                Button(onClick = { confirmClear = true }) { Text(stringResource(R.string.clear_cache)) }
            }
            InfoText(stringResource(R.string.clear_cache_note))
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.clear_cache)) },
        text = { Text(stringResource(R.string.clear_cache_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        // Only Widglet's own private files.
                        CutoutStore.get(ctx).clear()
                        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    }
                    Scheduler.refreshAll(ctx)
                    refresh++
                }
            }) { Text(stringResource(R.string.clear)) }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun EstimateCard(id: Int, c: WidgetConfig, count: Int, free: Long, onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sample by remember { mutableStateOf<Segmenter.Sample?>(null) }
    var busy by remember { mutableStateOf(false) }
    var quality by remember(c.quality) { mutableStateOf(c.quality) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(c.name.ifBlank { stringResource(R.string.widget_n, id) } + " · " + stringResource(R.string.n_photos, count), style = MaterialTheme.typography.titleMedium)
            EnumChips(stringResource(R.string.quality), Quality.entries, quality, { quality = it }, showNote = true)
            val s = sample
            when {
                busy -> { Text(stringResource(R.string.sampling)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                s == null -> FilledTonalButton(onClick = {
                    busy = true
                    scope.launch {
                        val uris = withContext(Dispatchers.IO) { PhotoSelector(ctx).resolve(c, 1L).photos.map { it.uri } }
                        sample = withContext(Dispatchers.Default) { Segmenter.sample(ctx, uris) }
                        busy = false
                    }
                }) { Text(stringResource(R.string.estimate_btn)) }
                s.modelMissing -> Text(stringResource(R.string.model_downloading))
                s.sampled == 0 -> Text(stringResource(R.string.estimate_failed))
                else -> {
                    val est = StorageMath.estimate(count, s.avgBytes.getValue(quality), s.noSubjectRate)
                    Text(stringResource(R.string.estimate_line, StorageMath.human(est), count, label(quality)), style = MaterialTheme.typography.titleSmall)
                    InfoText(stringResource(R.string.estimate_basis, s.sampled, (s.noSubjectRate * 100).toInt()))
                    val big = est > StorageMath.WARN_BYTES || est > free / 2
                    if (big && quality != Quality.LOW) {
                        val lower = Quality.entries[(quality.ordinal - 1).coerceAtLeast(0)]
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(stringResource(R.string.estimate_big, StorageMath.human(StorageMath.estimate(count, s.avgBytes.getValue(lower), s.noSubjectRate)), label(lower)))
                                TextButton(onClick = { quality = lower }) { Text(stringResource(R.string.use_x, label(lower))) }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (quality != c.quality) Button(onClick = { applyConfig(ctx, id, c.copy(quality = quality)); onRefresh() }) { Text(stringResource(R.string.apply_quality)) }
                if (c.cutout) OutlinedButton(onClick = { CutoutWorker.enqueueAll(ctx, id, c.copy(quality = quality)) }) { Text(stringResource(R.string.generate_all)) }
            }
            if (!c.cutout) InfoText(stringResource(R.string.cutout_off_note))
        }
    }
}

// ------------------------------------------------------------------ settings & stats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val repo = remember { Repo.get(ctx) }
    val scope = rememberCoroutineScope()
    val app by repo.app.collectAsState(initial = AppSettings())
    val stats by repo.stats.collectAsState(initial = Stats())
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SwitchRow(stringResource(R.string.pause_all), app.paused, stringResource(R.string.pause_all_sub)) { v ->
                        scope.launch { repo.updateApp { it.copy(paused = v) }; Scheduler.refreshAll(ctx) }
                    }
                }
            }
            LanguageCard()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.titleMedium)
                    InfoText(stringResource(R.string.stats_local))
                    Text(stringResource(R.string.stat_shown, stats.photosShown))
                    Text(stringResource(R.string.stat_cutouts, stats.cutoutsMade, stats.noSubject))
                    if (stats.sourceBytes > 0) {
                        val pct = (stats.cutoutBytes * 100 / stats.sourceBytes).toInt()
                        Text(stringResource(R.string.stat_saved, StorageMath.human(stats.cutoutBytes), StorageMath.human(stats.sourceBytes), pct))
                    }
                    if (stats.since > 0) InfoText(stringResource(R.string.stat_since, DateFormat.getDateInstance().format(Date(stats.since))))
                    TextButton(onClick = { scope.launch { repo.resetStats() } }) { Text(stringResource(R.string.reset_stats)) }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.onb_readonly))
                    Text(stringResource(R.string.onb_privacy))
                    Text(stringResource(R.string.about_tips))
                    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
                    InfoText(stringResource(R.string.version_x, version))
                    TextButton(onClick = { nav.go(Screen.Onboarding) }) { Text(stringResource(R.string.replay_onboarding)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LanguageCard() {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
            if (Build.VERSION.SDK_INT >= 33) {
                val lm = ctx.getSystemService(LocaleManager::class.java)
                val current = lm.applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language ?: ""
                Chips(null, listOf("", "en", "hi"), current, { code ->
                    when (code) { "en" -> "English"; "hi" -> "हिन्दी"; else -> stringResource(R.string.lang_system) }
                }) { code ->
                    lm.applicationLocales = if (code.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList(Locale.forLanguageTag(code))
                    WidgetUpdater.widgetIds(ctx).forEach { Scheduler.runNow(ctx, it, WidgetUpdater.Action.REFRESH) }
                }
            } else {
                InfoText(stringResource(R.string.lang_old_android))
            }
        }
    }
}
