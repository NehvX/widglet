package com.widglet.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.widglet.app.R
import com.widglet.app.data.AccessLevel
import com.widglet.app.data.AppSettings
import com.widglet.app.data.GalleryAccess
import com.widglet.app.data.Repo
import com.widglet.app.data.WidgetConfig
import com.widglet.app.widget.Scheduler
import com.widglet.app.widget.WidgetUpdater
import kotlinx.coroutines.launch

@Composable
fun rememberAccessLevel(): AccessLevel {
    val ctx = LocalContext.current
    var level by remember { mutableStateOf(GalleryAccess.get(ctx).accessLevel()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { level = GalleryAccess.get(ctx).accessLevel() }
    return level
}

@Composable
fun rememberPermissionRequest(onResult: () -> Unit = {}): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    return { launcher.launch(GalleryAccess.requiredPermissions()) }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val finish: () -> Unit = {
        scope.launch { Repo.get(ctx).updateApp { it.copy(onboarded = true) } }
        onDone()
    }
    val request = rememberPermissionRequest(onResult = finish)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onb_title), style = MaterialTheme.typography.displaySmall)
        Text(stringResource(R.string.onb_intro), style = MaterialTheme.typography.bodyLarge)
        OnbPoint(Icons.Default.Lock, stringResource(R.string.onb_readonly_t), stringResource(R.string.onb_readonly))
        OnbPoint(Icons.Default.PhotoLibrary, stringResource(R.string.onb_perm_t), stringResource(R.string.onb_perm))
        OnbPoint(Icons.Default.WifiOff, stringResource(R.string.onb_privacy_t), stringResource(R.string.onb_privacy))
        Spacer(Modifier.height(8.dp))
        Button(onClick = request, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.onb_allow)) }
        OutlinedButton(onClick = finish, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.onb_picker_only)) }
        Text(stringResource(R.string.onb_picker_note), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun OnbPoint(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val repo = remember { Repo.get(ctx) }
    val scope = rememberCoroutineScope()
    val configs by repo.configs.collectAsState(initial = emptyMap())
    val app by repo.app.collectAsState(initial = AppSettings())
    var liveIds by remember { mutableStateOf(WidgetUpdater.widgetIds(ctx).toSet()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { liveIds = WidgetUpdater.widgetIds(ctx).toSet() }
    val access = rememberAccessLevel()
    val request = rememberPermissionRequest()
    // Placed widgets that have no config yet still deserve a card.
    val widgets = liveIds.associateWith { configs[it] }

    LaunchedEffect(Unit) { Scheduler.syncAll(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { nav.go(Screen.Storage) }) { Icon(Icons.Default.SdStorage, stringResource(R.string.storage)) }
                    IconButton(onClick = { nav.go(Screen.Settings) }) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { requestPin(ctx, null) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.add_widget)) },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (access != AccessLevel.FULL) item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if (access == AccessLevel.PARTIAL) R.string.partial_title else R.string.noperm_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(if (access == AccessLevel.PARTIAL) R.string.partial_body else R.string.noperm_body), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = request) { Text(stringResource(if (access == AccessLevel.PARTIAL) R.string.select_more else R.string.grant)) }
                            TextButton(onClick = {
                                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                            }) { Text(stringResource(R.string.open_settings)) }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.pause_all), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.pause_all_sub), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = app.paused, onCheckedChange = { v ->
                            scope.launch { repo.updateApp { it.copy(paused = v) }; Scheduler.refreshAll(ctx) }
                        })
                    }
                }
            }
            if (widgets.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.PhotoLibrary, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.no_widgets), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.no_widgets_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
            items(widgets.entries.toList(), key = { it.key }) { (id, cfg) -> WidgetCard(id, cfg ?: WidgetConfig(), nav) }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun WidgetCard(id: Int, cfg: WidgetConfig, nav: Nav) {
    val ctx = LocalContext.current
    var thumbVersion by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { thumbVersion++ }
    val thumb = remember(thumbVersion) {
        WidgetUpdater.thumbFile(ctx, id).takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
    }
    Card(onClick = { nav.go(Screen.Editor(id)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.size(72.dp)) }
            }
            Column(Modifier.weight(1f)) {
                Text(cfg.name.ifBlank { stringResource(R.string.widget_n, id) }, style = MaterialTheme.typography.titleMedium)
                val src = when {
                    !cfg.hasSources -> stringResource(R.string.not_set_up)
                    else -> stringResource(R.string.sources_summary, cfg.albums.size, cfg.pickedUris.size)
                }
                Text(src, style = MaterialTheme.typography.bodySmall)
                Text(label(cfg.timing) + (if (cfg.cutout) " · " + stringResource(R.string.cutout) else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column {
                IconButton(onClick = { Scheduler.runNow(ctx, id, WidgetUpdater.Action.NEXT) }) { Icon(Icons.Default.SkipNext, stringResource(R.string.next_photo)) }
                IconButton(onClick = { requestPin(ctx, id) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.duplicate)) }
            }
            Icon(Icons.Default.Edit, stringResource(R.string.edit))
        }
    }
}
