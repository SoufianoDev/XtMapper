package xtr.keymapper.service

import xtr.keymapper.log.DiagnosticLog
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import xtr.keymapper.R
import xtr.keymapper.TouchPointer
import xtr.keymapper.activity.MainActivity
import xtr.keymapper.editor.EditorActivity
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfiles

class KeymapperTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val running = TouchPointer.isRunning
        DiagnosticLog.info("QuickSettings", "tile_clicked", "pointerRunning" to running, "backendConnected" to TouchPointer.backendConnected, "profile" to TouchPointer.currentSelectedProfile)

        if (running) {
            DiagnosticLog.info("QuickSettings", "tile_stop_requested", "hasActiveInstance" to (TouchPointer.activeInstance != null))
            val instance = TouchPointer.activeInstance
            if (instance != null) {
                instance.stopKeymapper()
            } else {
                val stopIntent = Intent(this, TouchPointer::class.java).apply {
                    action = TouchPointer.ACTION_STOP
                }
                startService(stopIntent)
            }
            TouchPointer.markStopped()
            updateTileState(false)
            Toast.makeText(this, "XtMapper stopped", Toast.LENGTH_SHORT).show()
        } else {
            if (!Settings.canDrawOverlays(this)) {
                DiagnosticLog.warn("QuickSettings", "tile_start_blocked", "reason" to "overlay_permission_missing")
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launchIntent(intent)
                Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
                return
            }

            val selectedProfile = TouchPointer.currentSelectedProfile
                ?: KeymapProfiles(this).getLastSelectedProfile()
            DiagnosticLog.info("QuickSettings", "tile_start_requested", "profile" to selectedProfile, "inputMode" to if (KeymapConfig(this).isShizukuMode) "shizuku" else "root")
            val startIntent = Intent(this, TouchPointer::class.java).apply {
                putExtra(EditorActivity.PROFILE_NAME, selectedProfile)
            }
            ContextCompat.startForegroundService(this, startIntent)
            TouchPointer.markStarting()
            updateTileState(false, selectedProfile)
            Toast.makeText(this, "Starting XtMapper ($selectedProfile)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchIntent(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState(
        isActive: Boolean = TouchPointer.isRunning && TouchPointer.backendConnected,
        profileName: String? = TouchPointer.currentSelectedProfile ?: KeymapProfiles(this).getLastSelectedProfile()
    ) {
        val tile = qsTile ?: return
        val nextState = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val nextSubtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isActive) {
                if (!profileName.isNullOrEmpty()) profileName else "Active"
            } else if (TouchPointer.isRunning) {
                "Connecting"
            } else {
                "Off"
            }
        } else null
        val changed = tile.state != nextState ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && tile.subtitle != nextSubtitle)
        if (changed) {
            DiagnosticLog.info("QuickSettings", "tile_state_changed", "state" to if (isActive) "active" else "inactive", "subtitle" to nextSubtitle, "pointerRunning" to TouchPointer.isRunning, "backendConnected" to TouchPointer.backendConnected, "profile" to profileName)
        }
        tile.state = nextState
        tile.label = getString(R.string.quick_settings_tile_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = nextSubtitle
        tile.updateTile()
    }

    companion object {
        fun updateTile(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, KeymapperTileService::class.java))
            } catch (e: Exception) {
                DiagnosticLog.warn("QuickSettings", "tile_listening_state_request_failed", "error" to e.message)
            }
        }
    }
}
