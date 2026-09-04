package ai.champi.app

import ai.champi.core.state.AppStateHolder
import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Quick Settings tile: ensures the overlay service is running, then requests the panel open. */
@AndroidEntryPoint
class ChampiTileService : TileService() {

    @Inject lateinit var appStateHolder: AppStateHolder

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            icon = Icon.createWithResource(this@ChampiTileService, R.drawable.ic_tile)
            label = getString(R.string.champi_tile_label)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (!Settings.canDrawOverlays(this)) return

        ContextCompat.startForegroundService(this, Intent(this, ChampiService::class.java))
        appStateHolder.requestOpenPanel()
    }
}
