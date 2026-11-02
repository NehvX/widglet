package com.widglet.app.ui

import android.content.Intent
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.widglet.app.R
import com.widglet.app.cutout.CutoutWorker
import com.widglet.app.data.Album
import com.widglet.app.data.AlbumRef
import com.widglet.app.data.BatteryImpact
import com.widglet.app.data.BuiltInPresets
import com.widglet.app.data.ClockPos
import com.widglet.app.data.CutoutBg
import com.widglet.app.data.DoubleTapAction
import com.widglet.app.data.Fit
import com.widglet.app.data.GalleryAccess
import com.widglet.app.data.NoSubjectAction
import com.widglet.app.data.Order
import com.widglet.app.data.Photo
import com.widglet.app.data.PhotoFilter
import com.widglet.app.data.PhotoSelector
import com.widglet.app.data.Quality
import com.widglet.app.data.Repo
import com.widglet.app.data.SettingsBackup
import com.widglet.app.data.Shape
import com.widglet.app.data.Speed
import com.widglet.app.data.StylePreset
import com.widglet.app.data.TapAction
import com.widglet.app.data.TextStyle
import com.widglet.app.data.TimeRule
import com.widglet.app.data.TimeSlot
import com.widglet.app.data.TimingMode
import com.widglet.app.data.Transition
import com.widglet.app.data.WidgetConfig
import com.widglet.app.data.WidgetLayout
import com.widglet.app.render.Renderer
import com.widglet.app.widget.WidgetUpdater
import com.widglet.app.widget.appScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(id: Int, nav: Nav, configMode: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Repo.get(ctx) }
    var cfg by remember(id) { mutableStateOf<WidgetConfig?>(null) }
    LaunchedEffect(id) { cfg = repo.config(id) ?: WidgetConfig() }

    // Autosave (debounced) and apply to the widget; flush on leave.
    val latest by rememberUpdatedState(cfg)
    var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(cfg) {
        val c = cfg ?: return@LaunchedEffect
        if (!dirty) return@LaunchedEffect
        delay(600)
        applyConfig(ctx, id, c)
        dirty = false
    }
    DisposableEffect(id) { onDispose { if (dirty) latest?.let { applyConfig(ctx, id, it) } } }
    fun set(c: WidgetConfig) { cfg = c; dirty = true }

    BackHandler(enabled = configMode) { latest?.let { applyConfig(ctx, id, it) }; onClose() }

    val c = cfg
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (configMode) R.string.setup_widget else R.string.edit_widget)) },
                navigationIcon = {
                    if (!configMode) IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    TextButton(onClick = { latest?.let { applyConfig(ctx, id, it) }; dirty = false; onClose() }) {
                        Icon(Icons.Default.Check, null); Spacer(Modifier.size(4.dp)); Text(stringResource(R.string.done))
                    }
                },
            )
        },
    ) { pad ->
        if (c == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item { PreviewPane(id, c) }
            item {
                OutlinedTextField(
                    value = c.name, onValueChange = { set(c.copy(name = it.take(40))) },
                    label = { Text(stringResource(R.string.widget_name)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { SourcesSection(id, c, nav, ::set) }
            item { SmartSection(c, ::set) }
            item { TimingSection(c, ::set) }
            item { TransitionSection(c, ::set) }
            item { CutoutSection(id, c, nav, ::set) }
            item { LookSection(c, ::set) }
            item { OverlaySection(c, ::set) }
            item { TapSection(c, ::set) }
            item { PresetSection(id, c, ::set) }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

// ------------------------------------------------------------------ preview

@Composable
private fun PreviewPane(id: Int, cfg: WidgetConfig) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val actual = remember(id) { WidgetUpdater.widgetSizePx(ctx, id).let { (w, h) -> (w / density).toInt() to (h / density).toInt() } }
    val sizes = listOf(actual, 160 to 160, 330 to 160, 330 to 330)
    var sizeIdx by rememberSaveable { mutableIntStateOf(0) }
    var offset by remember { mutableIntStateOf(0) }
    val sourceKey = cfg.withStyleOf(WidgetConfig()).copy(name = "")
    val photos by produceState<List<Photo>>(emptyList(), sourceKey) {
        value = withContext(Dispatchers.IO) { PhotoSelector(ctx).resolve(cfg, 1L).photos.take(40) }
    }
    var frame by remember { mutableStateOf<Renderer.Frame?>(null) }
    val (wDp, hDp) = sizes[sizeIdx]
    LaunchedEffect(cfg, photos, sizeIdx, offset) {
        delay(120)
        if (photos.isEmpty()) { frame = null; return@LaunchedEffect }
        val n = photos.size
        val list = (0 until minOf(4, n)).map { photos[(offset + it) % n] }
        val s = minOf(1f, 900f / (maxOf(wDp, hDp) * density))
        frame = withContext(Dispatchers.Default) {
            runCatching { Renderer(ctx).render(cfg, list, (wDp * density * s).toInt(), (hDp * density * s).toInt()) }.getOrNull()
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(wDp.dp, hDp.dp).background(
                androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF3A4A8A), Color(0xFF8A5A7A))),
                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            ),
            contentAlignment = Alignment.Center,
        ) {
            val f = frame
            if (f == null) {
                Text(stringResource(if (cfg.hasSources) R.string.preview_loading else R.string.preview_empty), color = Color.White, modifier = Modifier.padding(12.dp))
            } else {
                Image(f.back.asImageBitmap(), stringResource(R.string.preview_cd), Modifier.fillMaxSize())
                if (cfg.clock) PreviewClock(cfg, hDp)
                f.front?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Chips(null, sizes.indices.toList(), sizeIdx, { i ->
                if (i == 0) stringResource(R.string.size_actual) else listOf("", "2×2", "4×2", "4×4")[i]
            }) { sizeIdx = it }
            IconButton(onClick = { offset++ }) { Icon(Icons.Default.SkipNext, stringResource(R.string.next_photo)) }
        }
    }
}

@Composable
private fun PreviewClock(cfg: WidgetConfig, hDp: Int) {
    val ctx = LocalContext.current
    val now = remember { Date() }
    val align = when (cfg.clockPos) {
        ClockPos.TOP -> Alignment.TopCenter
        ClockPos.CENTER -> Alignment.Center
        ClockPos.BOTTOM -> Alignment.BottomCenter
    }
    Box(Modifier.fillMaxSize().padding(vertical = 10.dp), contentAlignment = align) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                DateFormat.getTimeFormat(ctx).format(now), color = Color(cfg.clockColor),
                fontSize = (hDp * 0.22f).coerceIn(18f, 120f).sp,
                fontFamily = if (cfg.clockMono) FontFamily.Monospace else FontFamily.SansSerif,
            )
            if (cfg.clockDate) Text(DateFormat.format("EEE, d MMM", now).toString(), color = Color(cfg.clockColor), fontSize = 13.sp)
        }
    }
}

// ------------------------------------------------------------------ sources

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesSection(id: Int, c: WidgetConfig, nav: Nav, set: (WidgetConfig) -> Unit) {
    val ctx = LocalContext.current
    val gallery = remember { GalleryAccess.get(ctx) }
    var pickAlbums by remember { mutableStateOf(false) }
    val access = rememberAccessLevel()
    val request = rememberPermissionRequest()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        uris.forEach(gallery::persistReadGrant)
        if (uris.isNotEmpty()) set(c.copy(pickedUris = (c.pickedUris + uris.map { it.toString() }).distinct()))
    }
    Section(stringResource(R.string.sec_photos), initiallyOpen = !c.hasSources) {
        InfoText(stringResource(R.string.readonly_reminder))
        Text(stringResource(R.string.albums), style = MaterialTheme.typography.labelLarge)
        if (c.albums.isEmpty()) InfoText(stringResource(R.string.no_albums_selected))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            c.albums.forEach { a ->
                InputChip(
                    selected = false, onClick = { set(c.copy(albums = c.albums - a)) }, label = { Text(a.name) },
                    trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.remove_x, a.name), Modifier.size(18.dp)) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { if (access == com.widglet.app.data.AccessLevel.NONE) request() else pickAlbums = true }) {
                Text(stringResource(R.string.choose_albums))
            }
            OutlinedButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(stringResource(R.string.pick_photos)) }
        }
        InfoText(stringResource(R.string.picked_count, c.pickedUris.size))
        if (c.pickedUris.isNotEmpty()) TextButton(onClick = { set(c.copy(pickedUris = emptyList())) }) { Text(stringResource(R.string.clear_picked)) }
        TextButton(onClick = { nav.go(Screen.Photos(id)) }) { Text(stringResource(R.string.manage_photos, c.hidden.size)) }
    }
    if (pickAlbums) AlbumPickerDialog(c.albums, onDismiss = { pickAlbums = false }) { set(c.copy(albums = it)); pickAlbums = false }
}

@Composable
fun AlbumPickerDialog(selected: List<AlbumRef>, onDismiss: () -> Unit, onConfirm: (List<AlbumRef>) -> Unit) {
    val ctx = LocalContext.current
    val albums by produceState<List<Album>?>(null) { value = withContext(Dispatchers.IO) { GalleryAccess.get(ctx).albums() } }
    var sel by remember { mutableStateOf(selected.map { it.id }.toSet()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
                Text(stringResource(R.string.choose_albums), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
                val list = albums
                if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                else if (list.isEmpty()) InfoText(stringResource(R.string.no_albums_found))
                LazyColumn(Modifier.weight(1f)) {
                    items(list ?: emptyList(), key = { it.id }) { a ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 72.dp)
                                .clickable { sel = if (a.id in sel) sel - a.id else sel + a.id }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Thumb(a.coverUri, 56.dp)
                            Column(Modifier.weight(1f)) {
                                Text(a.name, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.n_photos, a.count), style = MaterialTheme.typography.bodySmall)
                            }
                            Checkbox(checked = a.id in sel, onCheckedChange = null)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = {
                        val all = list ?: emptyList()
                        // Keep previously chosen albums that are currently not visible (e.g. partial access).
                        val kept = selected.filter { s -> s.id in sel && all.none { it.id == s.id } }
                        onConfirm(all.filter { it.id in sel }.map { AlbumRef(it.id, it.name) } + kept)
                    }) { Text(stringResource(R.string.ok)) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ smart selection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartSection(c: WidgetConfig, set: (WidgetConfig) -> Unit) {
    var datePick by remember { mutableStateOf<String?>(null) }
    var ruleSlot by remember { mutableStateOf<TimeSlot?>(null) }
    val df = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }
    Section(stringResource(R.string.sec_smart)) {
        SwitchRow(stringResource(R.string.on_this_day), c.onThisDay, stringResource(R.string.on_this_day_sub)) { set(c.copy(onThisDay = it)) }
        SwitchRow(stringResource(R.string.favourites_only), c.favouritesOnly, stringResource(R.string.favourites_sub)) { set(c.copy(favouritesOnly = it)) }
        SwitchRow(stringResource(R.string.skip_screenshots), c.skipScreenshots) { set(c.copy(skipScreenshots = it)) }
        SwitchRow(stringResource(R.string.skip_small), c.skipSmall, stringResource(R.string.skip_small_sub, c.minSidePx)) { set(c.copy(skipSmall = it)) }
        if (c.skipSmall) SliderRow(stringResource(R.string.min_side), c.minSidePx, 200..2000, " px") { set(c.copy(minSidePx = it / 50 * 50)) }
        SwitchRow(stringResource(R.string.skip_duplicates), c.skipDuplicates, stringResource(R.string.skip_duplicates_sub)) { set(c.copy(skipDuplicates = it)) }
        Text(stringResource(R.string.date_range), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { datePick = "from" }, label = { Text(stringResource(R.string.from_x, c.dateFrom?.let { df.format(Date(it)) } ?: stringResource(R.string.any))) })
            AssistChip(onClick = { datePick = "to" }, label = { Text(stringResource(R.string.to_x, c.dateTo?.let { df.format(Date(it)) } ?: stringResource(R.string.any))) })
            if (c.dateFrom != null || c.dateTo != null) TextButton(onClick = { set(c.copy(dateFrom = null, dateTo = null)) }) { Text(stringResource(R.string.clear)) }
        }
        SwitchRow(stringResource(R.string.time_rules), c.timeRulesEnabled, stringResource(R.string.time_rules_sub)) { set(c.copy(timeRulesEnabled = it)) }
        if (c.timeRulesEnabled) {
            TimeSlot.entries.forEach { slot ->
                val rule = c.timeRules.firstOrNull { it.slot == slot }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { ruleSlot = slot }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label(slot))
                        InfoText(rule?.albums?.takeIf { it.isNotEmpty() }?.joinToString { it.name } ?: stringResource(R.string.use_default_albums))
                    }
                    Text(stringResource(R.string.change), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    datePick?.let { which ->
        val state = rememberDatePickerState(initialSelectedDateMillis = if (which == "from") c.dateFrom else c.dateTo)
        DatePickerDialog(
            onDismissRequest = { datePick = null },
            confirmButton = {
                TextButton(onClick = {
                    val v = state.selectedDateMillis
                    set(if (which == "from") c.copy(dateFrom = v) else c.copy(dateTo = v)); datePick = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { datePick = null }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
    ruleSlot?.let { slot ->
        AlbumPickerDialog(c.timeRules.firstOrNull { it.slot == slot }?.albums ?: emptyList(), onDismiss = { ruleSlot = null }) { chosen ->
            set(c.copy(timeRules = c.timeRules.filterNot { it.slot == slot } + TimeRule(slot, chosen)))
            ruleSlot = null
        }
    }
}

// ------------------------------------------------------------------ timing

@Composable
private fun TimingSection(c: WidgetConfig, set: (WidgetConfig) -> Unit) {
    Section(stringResource(R.string.sec_timing)) {
        EnumChips(stringResource(R.string.when_change), TimingMode.entries, c.timing, { set(c.copy(timing = it)) }, showNote = true)
        if (c.timing == TimingMode.INTERVAL) {
            val presets = listOf(15, 30, 60, 180, 360, 720)
            Chips(stringResource(R.string.every), presets + -1, if (c.intervalMinutes in presets) c.intervalMinutes else -1, { m ->
                if (m == -1) stringResource(R.string.custom) else formatMinutes(m)
            }) { m -> if (m != -1) set(c.copy(intervalMinutes = m)) else if (c.intervalMinutes in presets) set(c.copy(intervalMinutes = 45)) }
            if (c.intervalMinutes !in presets) {
                SliderRow(stringResource(R.string.custom_interval), c.intervalMinutes, 15..1440, " min") { set(c.copy(intervalMinutes = (it / 5 * 5).coerceAtLeast(15))) }
            }
        }
        if (c.timing == TimingMode.SLIDESHOW) {
            Chips(stringResource(R.string.flip_every), listOf(5, 10, 30, 60, 300, 600), c.slideshowSeconds, { s ->
                if (s < 60) "${s}s" else "${s / 60} min"
            }) { set(c.copy(slideshowSeconds = it)) }
        }
        EnumChips(stringResource(R.string.order), Order.entries, c.order, { set(c.copy(order = it)) })
        SwitchRow(stringResource(R.string.skip_saver), c.skipInBatterySaver, stringResource(R.string.skip_saver_sub)) { set(c.copy(skipInBatterySaver = it)) }
        val lvl = BatteryImpact.of(c)
        val wake = BatteryImpact.wakeupsPerDay(c)
        Surface(color = when (lvl) {
            BatteryImpact.Level.LOW -> MaterialTheme.colorScheme.secondaryContainer
            BatteryImpact.Level.MEDIUM -> MaterialTheme.colorScheme.tertiaryContainer
            BatteryImpact.Level.HIGH -> MaterialTheme.colorScheme.errorContainer
        }, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(stringResource(R.string.battery_impact, label(lvl)), style = MaterialTheme.typography.titleSmall)
                InfoText(
                    when (wake) {
                        null -> stringResource(R.string.battery_event)
                        0 -> stringResource(R.string.battery_slideshow)
                        else -> stringResource(R.string.battery_wakeups, wake)
                    },
                )
            }
        }
    }
}

@Composable
private fun formatMinutes(m: Int): String = when {
    m % 60 == 0 && m >= 60 -> stringResource(R.string.hours_n, m / 60)
    else -> stringResource(R.string.minutes_n, m)
}

// ------------------------------------------------------------------ transitions

@Composable
private fun TransitionSection(c: WidgetConfig, set: (WidgetConfig) -> Unit) {
    Section(stringResource(R.string.sec_transition)) {
        EnumChips(null, Transition.entries, c.transition, { set(c.copy(transition = it)) }, showNote = true)
        if (c.transition != Transition.NONE && c.timing != TimingMode.SLIDESHOW) {
            EnumChips(stringResource(R.string.speed), Speed.entries, c.speed, { set(c.copy(speed = it)) })
        }
        InfoText(stringResource(R.string.transition_limits))
    }
}

// ------------------------------------------------------------------ cutout

@Composable
private fun CutoutSection(id: Int, c: WidgetConfig, nav: Nav, set: (WidgetConfig) -> Unit) {
    val ctx = LocalContext.current
    val infos by WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(CutoutWorker.uniqueName(id)).collectAsState(initial = emptyList())
    val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    Section(stringResource(R.string.sec_cutout)) {
        SwitchRow(stringResource(R.string.cutout_mode), c.cutout, stringResource(R.string.cutout_mode_sub)) { set(c.copy(cutout = it)) }
        if (!c.cutout) return@Section
        EnumChips(stringResource(R.string.cutout_bg), CutoutBg.entries, c.cutoutBg, { set(c.copy(cutoutBg = it)) }, showNote = true)
        if (c.cutoutBg == CutoutBg.SOLID || c.cutoutBg == CutoutBg.GRADIENT) {
            ColorRow(stringResource(R.string.bg_color_cutout), c.bgColor2, allowTransparent = false) { set(c.copy(bgColor2 = it)) }
            if (c.cutoutBg == CutoutBg.GRADIENT) ColorRow(stringResource(R.string.gradient_end), c.accentColor, allowTransparent = false) { set(c.copy(accentColor = it)) }
        }
        EnumChips(stringResource(R.string.no_subject), NoSubjectAction.entries, c.noSubject, { set(c.copy(noSubject = it)) })
        EnumChips(stringResource(R.string.quality), Quality.entries, c.quality, { set(c.copy(quality = it)) }, showNote = true)
        TextButton(onClick = { nav.go(Screen.Storage) }) { Text(stringResource(R.string.see_estimate)) }
        SwitchRow(stringResource(R.string.prefer_subjects), c.preferSubjects, stringResource(R.string.prefer_subjects_sub)) { set(c.copy(preferSubjects = it)) }
        SwitchRow(stringResource(R.string.lazy_mode), c.lazyCutouts, stringResource(R.string.lazy_mode_sub, c.lazyCount)) { set(c.copy(lazyCutouts = it)) }
        if (c.lazyCutouts) SliderRow(stringResource(R.string.lazy_count), c.lazyCount, 3..50) { set(c.copy(lazyCount = it)) }
        SwitchRow(stringResource(R.string.only_charging), c.processOnlyCharging) { set(c.copy(processOnlyCharging = it)) }
        SwitchRow(stringResource(R.string.only_wifi), c.processOnlyWifi, stringResource(R.string.only_wifi_sub)) { set(c.copy(processOnlyWifi = it)) }
        SwitchRow(stringResource(R.string.shadow), c.shadow) { set(c.copy(shadow = it)) }
        if (c.shadow) {
            SliderRow(stringResource(R.string.softness), c.shadowSoftness, 1..32, " dp") { set(c.copy(shadowSoftness = it)) }
            SliderRow(stringResource(R.string.offset), c.shadowOffset, 0..24, " dp") { set(c.copy(shadowOffset = it)) }
        }
        SwitchRow(stringResource(R.string.sticker_outline), c.outline) { set(c.copy(outline = it)) }
        if (c.outline) {
            SliderRow(stringResource(R.string.thickness), c.outlineWidth, 1..16, " dp") { set(c.copy(outlineWidth = it)) }
            ColorRow(stringResource(R.string.outline_color), c.outlineColor, allowTransparent = false) { set(c.copy(outlineColor = it)) }
        }
        if (running != null) {
            val done = running.progress.getInt(CutoutWorker.P_DONE, 0)
            val total = running.progress.getInt(CutoutWorker.P_TOTAL, 0)
            val missing = running.progress.getBoolean(CutoutWorker.P_MODEL_MISSING, false)
            Text(
                when {
                    missing -> stringResource(R.string.model_downloading)
                    running.state == WorkInfo.State.ENQUEUED -> stringResource(R.string.cutouts_waiting)
                    else -> stringResource(R.string.cutouts_progress, done, total)
                },
            )
            if (total > 0) LinearProgressIndicator(progress = { done / total.toFloat() }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { WorkManager.getInstance(ctx).cancelUniqueWork(CutoutWorker.uniqueName(id)) }) { Text(stringResource(R.string.cancel)) }
        } else {
            FilledTonalButton(onClick = {
                applyConfig(ctx, id, c)
                CutoutWorker.enqueueAll(ctx, id, c)
            }) { Text(stringResource(R.string.generate_all)) }
            InfoText(stringResource(R.string.cutout_model_note))
        }
    }
}

// ------------------------------------------------------------------ look

@Composable
private fun LookSection(c: WidgetConfig, set: (WidgetConfig) -> Unit) {
    Section(stringResource(R.string.sec_look)) {
        EnumChips(stringResource(R.string.layout), WidgetLayout.entries, c.layout, { set(c.copy(layout = it)) }, showNote = true)
        if (c.layout == WidgetLayout.COLLAGE) Chips(stringResource(R.string.collage_count), listOf(2, 3, 4), c.collageCount, { "$it" }) { set(c.copy(collageCount = it)) }
        EnumChips(stringResource(R.string.shape), Shape.entries, c.shape, { set(c.copy(shape = it)) })
        if (c.shape == Shape.ROUNDED) SliderRow(stringResource(R.string.corner_radius), c.cornerRadius, 0..64, " dp") { set(c.copy(cornerRadius = it)) }
        SliderRow(stringResource(R.string.padding), c.padding, 0..32, " dp") { set(c.copy(padding = it)) }
        EnumChips(stringResource(R.string.fit), Fit.entries, c.fit, { set(c.copy(fit = it)) }, showNote = true)
        EnumChips(stringResource(R.string.filter), PhotoFilter.entries, c.filter, { set(c.copy(filter = it)) })
        ColorRow(stringResource(R.string.widget_bg), c.bgColor, showAlpha = true) { set(c.copy(bgColor = it)) }
        SwitchRow(stringResource(R.string.border), c.border) { set(c.copy(border = it)) }
        if (c.border) {
            SliderRow(stringResource(R.string.border_width), c.borderWidth, 1..12, " dp") { set(c.copy(borderWidth = it)) }
            ColorRow(stringResource(R.string.border_color), c.borderColor, allowTransparent = false) { set(c.copy(borderColor = it)) }
        }
    }
}

@Composable
private fun OverlaySection(c: WidgetConfig, set: (WidgetConfig) -> Unit) {
    Section(stringResource(R.string.sec_overlay)) {
        SwitchRow(stringResource(R.string.show_date), c.showDate) { set(c.copy(showDate = it)) }
        SwitchRow(stringResource(R.string.show_album), c.showAlbum) { set(c.copy(showAlbum = it)) }
        if (c.showDate || c.showAlbum) {
            EnumChips(stringResource(R.string.text_style), TextStyle.entries, c.textStyle, { set(c.copy(textStyle = it)) })
            if (c.textStyle == TextStyle.DOT_MATRIX) ColorRow(stringResource(R.string.accent), c.accentColor, allowTransparent = false) { set(c.copy(accentColor = it)) }
        }
        SwitchRow(stringResource(R.string.clock), c.clock, stringResource(R.string.clock_sub)) { set(c.copy(clock = it)) }
        if (c.clock) {
            EnumChips(stringResource(R.string.clock_pos), ClockPos.entries, c.clockPos, { set(c.copy(clockPos = it)) })
            SwitchRow(stringResource(R.string.clock_mono), c.clockMono) { set(c.copy(clockMono = it)) }
            SwitchRow(stringResource(R.string.clock_date), c.clockDate) { set(c.copy(clockDate = it)) }
            ColorRow(stringResource(R.string.clock_color), c.clockColor, allowTransparent = false) { set(c.copy(clockColor = it)) }
            if (!c.cutout || c.cutoutBg != CutoutBg.ORIGINAL) {
                TextButton(onClick = { set(c.copy(cutout = true, cutoutBg = CutoutBg.ORIGINAL)) }) { Text(stringResource(R.string.make_depth)) }
            }
        }
    }
}

@Composable
private fun TapSection(c: WidgetConfig, set: (WidgetConfig) -> Unit) {
    Section(stringResource(R.string.sec_tap)) {
        EnumChips(stringResource(R.string.on_tap), TapAction.entries, c.tap, { set(c.copy(tap = it)) })
        if (c.tap == TapAction.NEXT && c.timing != TimingMode.SLIDESHOW) {
            EnumChips(stringResource(R.string.on_double_tap), DoubleTapAction.entries, c.doubleTap, { set(c.copy(doubleTap = it)) })
            if (c.doubleTap != DoubleTapAction.NONE) InfoText(stringResource(R.string.double_tap_note))
        }
        InfoText(stringResource(R.string.long_press_note))
    }
}

// ------------------------------------------------------------------ presets, copy, backup

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetSection(id: Int, c: WidgetConfig, set: (WidgetConfig) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Repo.get(ctx) }
    val saved by repo.presets.collectAsState(initial = emptyList())
    val others by repo.configs.collectAsState(initial = emptyMap())
    val toast = rememberToast()
    var saveDialog by remember { mutableStateOf(false) }
    var importDialog by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) toast(ctx.getString(if (SettingsBackup.export(ctx, uri, c)) R.string.exported else R.string.export_failed))
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val imported = SettingsBackup.import(ctx, uri)
            if (imported != null) { set(imported); toast(ctx.getString(R.string.imported)) } else toast(ctx.getString(R.string.import_failed))
        }
    }
    fun apply(p: StylePreset) = set(c.withStyleOf(p.style).copy(cutout = c.cutout || p.style.cutout))

    Section(stringResource(R.string.sec_presets)) {
        Text(stringResource(R.string.builtin_presets), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BuiltInPresets.all.forEach { p -> AssistChip(onClick = { apply(p) }, label = { Text(p.name) }) }
        }
        if (saved.isNotEmpty()) {
            Text(stringResource(R.string.my_presets), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                saved.forEach { p ->
                    InputChip(
                        selected = false, onClick = { apply(p) }, label = { Text(p.name) },
                        trailingIcon = {
                            Icon(Icons.Default.Close, stringResource(R.string.remove_x, p.name), Modifier.size(18.dp).clickable {
                                appScope.launch { repo.deletePreset(p.name) }
                            })
                        },
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { saveDialog = true }) { Text(stringResource(R.string.save_preset)) }
            OutlinedButton(onClick = {
                val text = SettingsBackup.presetToText(StylePreset(c.name.ifBlank { "Widglet style" }, c.withStyleOf(c)))
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }) { Text(stringResource(R.string.share_preset)) }
            OutlinedButton(onClick = { importDialog = true }) { Text(stringResource(R.string.paste_preset)) }
        }
        val copyable = others.filterKeys { it != id }
        if (copyable.isNotEmpty()) {
            Text(stringResource(R.string.copy_from), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                copyable.forEach { (oid, o) ->
                    AssistChip(onClick = { set(o.copy(name = c.name)) }, label = { Text(o.name.ifBlank { ctx.getString(R.string.widget_n, oid) }) })
                }
            }
        }
        Text(stringResource(R.string.backup), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportLauncher.launch("widglet-${c.name.ifBlank { "widget" }}.json") }) { Text(stringResource(R.string.export)) }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text(stringResource(R.string.import_)) }
        }
        InfoText(stringResource(R.string.backup_note))
    }

    if (saveDialog) {
        var name by remember { mutableStateOf(c.name) }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text(stringResource(R.string.save_preset)) },
            text = { OutlinedTextField(name, { name = it.take(30) }, label = { Text(stringResource(R.string.preset_name)) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    appScope.launch { repo.savePreset(StylePreset(name.trim(), c)) }
                    saveDialog = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (importDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importDialog = false },
            title = { Text(stringResource(R.string.paste_preset)) },
            text = { OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.preset_json)) }, minLines = 3) },
            confirmButton = {
                TextButton(onClick = {
                    val p = SettingsBackup.presetFromText(text)
                    if (p != null) { appScope.launch { repo.savePreset(p) }; apply(p); importDialog = false } else toast(ctx.getString(R.string.import_failed))
                }) { Text(stringResource(R.string.import_)) }
            },
            dismissButton = { TextButton(onClick = { importDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
