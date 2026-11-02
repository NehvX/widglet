package com.widglet.app.tiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.widglet.app.R
import com.widglet.app.data.Repo
import com.widglet.app.widget.Scheduler
import com.widglet.app.widget.WidgetUpdater
import com.widglet.app.widget.appScope
import kotlinx.coroutines.launch

/** Quick Settings tile: skip every widget to its next photo. */
class NextPhotoTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = getString(R.string.tile_next); updateTile() }
    }

    override fun onClick() {
        Scheduler.runNow(applicationContext, null, WidgetUpdater.Action.NEXT)
    }
}

/** Quick Settings tile: pause / resume all widgets. */
class PauseTile : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        appScope.launch {
            Repo.get(applicationContext).updateApp { it.copy(paused = !it.paused) }
            Scheduler.refreshAll(applicationContext)
            render()
        }
    }

    private fun render() {
        appScope.launch {
            val paused = Repo.get(applicationContext).appNow().paused
            qsTile?.apply {
                state = if (paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                label = getString(R.string.tile_pause)
                subtitle = getString(if (paused) R.string.paused else R.string.running)
                updateTile()
            }
        }
    }
}
