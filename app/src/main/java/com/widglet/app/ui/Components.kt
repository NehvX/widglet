package com.widglet.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.widglet.app.R
import com.widglet.app.data.GalleryAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun WidgletTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = try {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } catch (_: Exception) {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

/** Localised label for any settings enum: looks up R.string.e_<enum>_<value>, falling back to a readable name. */
@SuppressLint("DiscouragedApi")
fun enumLabel(ctx: Context, e: Enum<*>, suffix: String = ""): String {
    val name = "e_${e.javaClass.simpleName.lowercase()}_${e.name.lowercase()}$suffix"
    val id = ctx.resources.getIdentifier(name, "string", ctx.packageName)
    return if (id != 0) ctx.getString(id) else if (suffix.isEmpty()) e.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } else ""
}

@Composable
fun label(e: Enum<*>): String = enumLabel(LocalContext.current, e)

@Composable
fun note(e: Enum<*>): String = enumLabel(LocalContext.current, e, "_note")

@Composable
fun Section(title: String, initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(role = Role.Button) { open = !open }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
        if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T : Enum<T>> EnumChips(title: String?, values: List<T>, selected: T, onSelect: (T) -> Unit, showNote: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { v ->
                FilterChip(
                    selected = v == selected, onClick = { onSelect(v) }, label = { Text(label(v)) },
                    leadingIcon = if (v == selected) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                )
            }
        }
        if (showNote) {
            val n = note(selected)
            if (n.isNotBlank()) InfoText(n)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Chips(title: String?, values: List<T>, selected: T, text: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { v -> FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(text(v)) }) }
        }
    }
}

@Composable
fun SliderRow(title: String, value: Int, range: IntRange, unit: String = "", onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("$value$unit", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat(), onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

private val PALETTE = listOf(
    0x00000000, 0xFFFFFFFF, 0xFF000000, 0xFF202124, 0xFF9E9E9E, 0xFFD71921, 0xFFFF7A59, 0xFFFFC107,
    0xFF4CAF50, 0xFF26A69A, 0xFF29B6F6, 0xFF5B6CFF, 0xFF8E5BFF, 0xFFEC407A, 0xFF795548, 0xFFF5E6CC,
).map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorRow(title: String, color: Int, allowTransparent: Boolean = true, showAlpha: Boolean = false, onChange: (Int) -> Unit) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PALETTE.filter { allowTransparent || it != 0 }.forEach { c ->
                val sel = (c or (0xFF shl 24)) == (color or (0xFF shl 24)) && (c == 0) == (color == 0)
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(if (c == 0) Color.LightGray.copy(alpha = 0.3f) else Color(c))
                        .border(BorderStroke(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline), CircleShape)
                        .clickable(role = Role.RadioButton) {
                            val a = if (showAlpha && color != 0) (color ushr 24) else 0xFF
                            onChange(if (c == 0) 0 else (c and 0x00FFFFFF) or (a shl 24))
                        }
                        .semantics { contentDescription = if (c == 0) ctx.getString(R.string.color_transparent) else "#%06X".format(c and 0xFFFFFF) },
                    contentAlignment = Alignment.Center,
                ) { if (c == 0) Text("∅", color = MaterialTheme.colorScheme.onSurface) }
            }
        }
        if (showAlpha && color != 0) {
            SliderRow(ctx.getString(R.string.opacity), ((color ushr 24) * 100 / 255), 5..100, "%") { pct ->
                onChange((color and 0x00FFFFFF) or ((pct * 255 / 100) shl 24))
            }
        }
    }
}

@Composable
fun InfoText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/** Async, cached thumbnail of a gallery photo (read-only). */
@Composable
fun Thumb(uri: String, size: Dp, modifier: Modifier = Modifier, description: String? = null) {
    val ctx = LocalContext.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }.coerceAtMost(512)
    val bmp by produceState<Bitmap?>(thumbCache.get("$uri@$px"), uri, px) {
        if (value == null) value = withContext(Dispatchers.IO) {
            GalleryAccess.get(ctx).thumbnail(uri, px)?.also { thumbCache.put("$uri@$px", it) }
        }
    }
    Box(modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it.asImageBitmap(), description, contentScale = ContentScale.Crop, modifier = Modifier.size(size)) }
    }
}

@Composable
fun rememberToast(): (String) -> Unit {
    val ctx = LocalContext.current
    return remember { { msg: String -> android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show() } }
}
