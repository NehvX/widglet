package com.widglet.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as BitmapCanvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.widglet.app.R
import com.widglet.app.cutout.CutoutStore
import com.widglet.app.data.GalleryAccess
import com.widglet.app.data.Photo
import com.widglet.app.data.PhotoSelector
import com.widglet.app.data.Repo
import com.widglet.app.data.WidgetConfig
import com.widglet.app.widget.Scheduler
import com.widglet.app.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/** Every photo a widget can show; tap to hide it from this widget (inside Widglet only) or touch up its cutout. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(id: Int, nav: Nav) {
    val ctx = LocalContext.current
    val repo = remember { Repo.get(ctx) }
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf<WidgetConfig?>(null) }
    var photos by remember { mutableStateOf<List<Photo>?>(null) }
    var selected by remember { mutableStateOf<Photo?>(null) }
    LaunchedEffect(id) {
        val c = repo.config(id) ?: WidgetConfig()
        cfg = c
        photos = withContext(Dispatchers.IO) {
            PhotoSelector(ctx).resolve(c.copy(hidden = emptySet(), onThisDay = false, timeRulesEnabled = false), 1L).photos
        }
    }
    val store = remember { CutoutStore.get(ctx) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.manage_photos_title)) },
            navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        val c = cfg
        val list = photos
        Column(Modifier.padding(pad)) {
            InfoText(stringResource(R.string.hide_note).let { "  $it" })
            if (list == null || c == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            else LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                items(list, key = { it.uri }) { p ->
                    val hidden = p.uri in c.hidden
                    Box(Modifier.padding(4.dp).aspectRatio(1f).clickable { selected = p }) {
                        Thumb(p.uri, 104.dp, Modifier.alpha(if (hidden) 0.35f else 1f), description = p.displayName)
                        if (hidden) Icon(Icons.Default.VisibilityOff, stringResource(R.string.hidden), Modifier.align(Alignment.Center), tint = Color.White)
                        if (c.cutout && store.status(p.uri, c.quality) == CutoutStore.Status.READY) {
                            Icon(Icons.Default.ContentCut, stringResource(R.string.cutout_ready), Modifier.align(Alignment.TopEnd).padding(4.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)).padding(2.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
    }
    val sel = selected
    val c = cfg
    if (sel != null && c != null) {
        val hidden = sel.uri in c.hidden
        val status = store.status(sel.uri, c.quality)
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(sel.displayName ?: stringResource(R.string.photo)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Thumb(sel.uri, 200.dp)
                    if (c.cutout) Text(stringResource(when (status) {
                        CutoutStore.Status.READY -> R.string.cutout_ready
                        CutoutStore.Status.NO_SUBJECT -> R.string.cutout_none
                        CutoutStore.Status.MISSING -> R.string.cutout_missing
                    }))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = c.copy(hidden = if (hidden) c.hidden - sel.uri else c.hidden + sel.uri)
                    cfg = n
                    scope.launch { repo.saveConfig(id, n); Scheduler.runNow(ctx, id, WidgetUpdater.Action.REFRESH) }
                    selected = null
                }) { Text(stringResource(if (hidden) R.string.unhide else R.string.hide)) }
            },
            dismissButton = {
                if (c.cutout && status == CutoutStore.Status.READY) {
                    TextButton(onClick = { selected = null; nav.go(Screen.TouchUp(id, sel.uri)) }) { Text(stringResource(R.string.touch_up)) }
                } else TextButton(onClick = { selected = null }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

/** Manual cutout touch-up: paint to restore or erase parts of the subject. Saved only in Widglet's private storage. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TouchUpScreen(id: Int, uri: String, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { CutoutStore.get(ctx) }
    var work by remember { mutableStateOf<Bitmap?>(null) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var cfg by remember { mutableStateOf<WidgetConfig?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var erase by remember { mutableStateOf(true) }
    var brush by remember { mutableIntStateOf(24) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        val c = Repo.get(ctx).config(id) ?: WidgetConfig()
        cfg = c
        withContext(Dispatchers.IO) {
            val cut = store.load(uri, c.quality)?.copy(Bitmap.Config.ARGB_8888, true)
            val orig = cut?.let { GalleryAccess.get(ctx).decode(uri, it.width, it.height, fitInside = false) }
                ?.let { Bitmap.createScaledBitmap(it, cut.width, cut.height, true) }
            work = cut; original = orig
        }
    }

    val erasePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) } }
    val restorePaint = remember(original) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { original?.let { shader = BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) } }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.touch_up)) },
            navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                TextButton(enabled = work != null && !saving, onClick = {
                    val w = work ?: return@TextButton
                    val c = cfg ?: return@TextButton
                    saving = true
                    scope.launch {
                        withContext(Dispatchers.IO) { store.save(uri, c.quality, w) }
                        Scheduler.runNow(ctx, id, WidgetUpdater.Action.REFRESH)
                        nav.back()
                    }
                }) { Text(stringResource(R.string.save)) }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val w = work
            if (w == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                return@Column
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                SegmentedButton(selected = erase, onClick = { erase = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.erase)) }
                SegmentedButton(selected = !erase, onClick = { erase = false }, shape = SegmentedButtonDefaults.itemShape(1, 2), enabled = original != null) {
                    Text(stringResource(R.string.restore))
                }
            }
            Column(Modifier.padding(horizontal = 16.dp)) { SliderRow(stringResource(R.string.brush_size), brush, 4..96, " px") { brush = it } }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(16.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF808080))) {
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val scale = min(boxW / w.width, boxH / w.height)
                val ox = (boxW - w.width * scale) / 2
                val oy = (boxH - w.height * scale) / 2
                fun paintAt(o: Offset) {
                    val x = (o.x - ox) / scale
                    val y = (o.y - oy) / scale
                    BitmapCanvas(w).drawCircle(x, y, brush / scale.coerceAtLeast(0.1f) * 0.5f, if (erase) erasePaint else restorePaint)
                    version++
                }
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(erase, brush, scale) { detectTapGestures { paintAt(it) } }
                        .pointerInput(erase, brush, scale) { detectDragGestures { change, _ -> paintAt(change.position) } },
                ) {
                    // Checkerboard so transparency is visible.
                    val cell = 16.dp.toPx()
                    var yy = 0f
                    var row = 0
                    while (yy < size.height) {
                        var xx = if (row % 2 == 0) 0f else cell
                        while (xx < size.width) { drawRect(Color(0xFFBDBDBD), Offset(xx, yy), Size(cell, cell)); xx += cell * 2 }
                        yy += cell; row++
                    }
                    if (version < 0) return@Canvas // reading `version` redraws after each brush stroke
                    drawImage(
                        w.asImageBitmap(), srcOffset = IntOffset.Zero, srcSize = IntSize(w.width, w.height),
                        dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize((w.width * scale).toInt(), (w.height * scale).toInt()),
                    )
                }
            }
            Row(Modifier.padding(16.dp)) { InfoText(stringResource(R.string.touch_up_note)) }
        }
    }
}
