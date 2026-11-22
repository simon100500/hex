package app.hexforandroid

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The Quick Settings tile: loads the speech model and arms dictation, or
 * unloads it. It never starts a recording; dictation still starts from the
 * mic key over the keyboard.
 */
class DictationTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (DictationService.running) {
            DictationService.stop(this)
            render()
            return
        }
        // A microphone foreground service may only start while Hex is in
        // front; ArmActivity is the transparent bridge that does it.
        val intent = Intent(this, ArmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
    }

    @SuppressLint("NewApi")
    private fun render() {
        val tile = qsTile ?: return
        val on = DictationService.running
        val phase = Dictation.state.phase
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "GigaAM"
        tile.subtitle = when {
            !on -> "Модель выгружена"
            phase == Dictation.Phase.Loading -> "Загрузка модели…"
            phase == Dictation.Phase.Recording -> "Слушает"
            phase == Dictation.Phase.Transcribing -> "Распознаёт"
            else -> "Модель загружена"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_hex)
        tile.updateTile()
    }
}
