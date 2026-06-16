package com.titan.automation.engine.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.titan.automation.R
import com.titan.automation.engine.playback.SimplePlaybackEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Quick Settings tile — lets users start/stop TITAN directly from the notification shade
 * without opening the app.  Uses [EntryPointAccessors] to obtain the singleton
 * [SimplePlaybackEngine] safely from a [TileService] context.
 *
 * Declared in AndroidManifest with BIND_QUICK_SETTINGS_TILE permission.
 * Requires Android 7+ (API 24).
 */
class AutomationTileService : TileService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TileEntryPoint {
        fun simplePlaybackEngine(): SimplePlaybackEngine
    }

    private val engine: SimplePlaybackEngine
        get() = EntryPointAccessors.fromApplication(
            applicationContext,
            TileEntryPoint::class.java
        ).simplePlaybackEngine()

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val e = engine
        if (e.isPlaying.value) {
            e.stop()
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val e = engine
        val running = e.isPlaying.value
        tile.state    = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label    = "TITAN"
        tile.subtitle = when {
            running && e.currentMacroName.value != null ->
                e.currentMacroName.value!!.take(18)
            running -> "Running"
            else    -> "Ready"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_titan)
        tile.updateTile()
    }
}
