package com.moodwatch.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** After an app update, pushes the newly bundled watch face over an older installed one. */
class FaceUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED || !MoodFace.isSupported()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MoodFace.ensureInstalled(context, addIfMissing = false)
            } finally {
                pending.finish()
            }
        }
    }
}
