package com.titan.automation.engine.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.titan.automation.engine.playback.SimplePlaybackEngine
import com.titan.automation.engine.workflow.MacroEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Handles notification action taps (e.g. "Stop All" from the control notification).
 * Uses [EntryPointAccessors] since [BroadcastReceiver] cannot be a Hilt injection target
 * when declared in the manifest as an exported receiver.
 */
class ControlActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun simplePlaybackEngine(): SimplePlaybackEngine
        fun macroEngine(): MacroEngine
    }

    override fun onReceive(context: Context, intent: Intent) {
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReceiverEntryPoint::class.java
        )
        when (intent.action) {
            ControlNotificationManager.ACTION_STOP_ALL -> {
                entry.simplePlaybackEngine().stop()
                entry.macroEngine().pauseAll()
            }
        }
    }
}
