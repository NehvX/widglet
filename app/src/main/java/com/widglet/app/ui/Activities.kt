package com.widglet.app.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.widglet.app.R
import com.widglet.app.data.Repo
import com.widglet.app.data.WidgetConfig
import com.widglet.app.widget.PhotoWidgetProvider
import com.widglet.app.widget.PinnedReceiver
import com.widglet.app.widget.Scheduler
import com.widglet.app.widget.WidgetUpdater
import com.widglet.app.widget.appScope
import android.app.PendingIntent
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val openWidget = mutableIntStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openWidget.intValue = intent.getIntExtra(WidgetUpdater.EXTRA_WIDGET_ID, -1)
        setContent { WidgletTheme { AppRoot(openWidget = openWidget.intValue, configMode = false, onConfigDone = {}) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openWidget.intValue = intent.getIntExtra(WidgetUpdater.EXTRA_WIDGET_ID, -1)
    }
}

/** Opens when a widget is added (and when the launcher offers "reconfigure"). */
class ConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        // If the user backs out of the first setup, the launcher drops the widget.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        setContent {
            WidgletTheme {
                AppRoot(openWidget = id, configMode = true, onConfigDone = {
                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    finish()
                })
            }
        }
    }
}

/** Targets of the launcher app shortcuts (long-press the app icon). */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            "com.widglet.app.ADD_WIDGET" -> requestPin(this, null)
            "com.widglet.app.NEXT_PHOTO" -> {
                Scheduler.runNow(this, null, WidgetUpdater.Action.NEXT)
                Toast.makeText(this, R.string.toast_next, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}

/** Opens a photo in the user's gallery/viewer app (read-only grant). */
class ViewPhotoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.getStringExtra(WidgetUpdater.EXTRA_URI)
        if (uri != null) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.no_viewer, Toast.LENGTH_SHORT).show()
            } catch (_: SecurityException) {
                Toast.makeText(this, R.string.no_viewer, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}

/**
 * Ask the launcher to add a widget. [copyFrom] duplicates that widget's settings into the new one.
 * Returns false if the launcher doesn't support pinning (the user then adds it from the widget picker).
 */
fun requestPin(context: Context, copyFrom: Int?): Boolean {
    val mgr = AppWidgetManager.getInstance(context)
    if (!mgr.isRequestPinAppWidgetSupported) {
        Toast.makeText(context, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
        return false
    }
    val cb = Intent(context, PinnedReceiver::class.java).setData(Uri.parse("widglet://pin/${copyFrom ?: -1}/${System.nanoTime()}"))
    copyFrom?.let { cb.putExtra(PinnedReceiver.EXTRA_SOURCE, it) }
    val pi = PendingIntent.getBroadcast(context, 0, cb, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    return mgr.requestPinAppWidget(ComponentName(context, PhotoWidgetProvider::class.java), null, pi)
}

/** Save a config and apply it (schedule + redraw). */
fun applyConfig(context: Context, id: Int, cfg: WidgetConfig) {
    appScope.launch {
        Repo.get(context).saveConfig(id, cfg)
        Scheduler.sync(context, id, cfg)
        Scheduler.runNow(context, id, WidgetUpdater.Action.REFRESH)
    }
}
