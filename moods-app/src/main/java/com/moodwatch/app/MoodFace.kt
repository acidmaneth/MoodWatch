package com.moodwatch.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.wear.watchfacepush.WatchFacePushManager
import androidx.wear.watchfacepush.WatchFacePushManager.AddWatchFaceException
import androidx.wear.watchfacepush.WatchFacePushManager.UpdateWatchFaceException
import androidx.wear.watchfacepush.WatchFacePushManagerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Installs the Moods watch face bundled in the app's assets, using Watch Face Push. */
object MoodFace {
    const val PACKAGE = "com.moodwatch.app.watchfacepush.moods"
    const val SET_ACTIVE_PERMISSION = "com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE"

    private const val TAG = "MoodFace"
    private const val APK_ASSET = "default_watchface.apk"
    private const val TOKEN_ASSET = "default_watchface_token.txt"

    sealed interface Status {
        data object Unsupported : Status
        data object NoFreeSlot : Status
        data class Installed(val slotId: String, val active: Boolean) : Status
        data class Failed(val message: String) : Status
    }

    private class Bundled(val apk: File, val versionCode: Long, val token: String)

    private val lock = Mutex()

    fun isSupported(): Boolean = WatchFacePushManagerFactory.isSupported()

    /**
     * Adds the bundled face if it isn't installed (only when [addIfMissing]), or updates it if
     * the installed one is older, and reports whether it's the active watch face.
     */
    suspend fun ensureInstalled(context: Context, addIfMissing: Boolean = true): Status =
        withContext(Dispatchers.IO) {
            if (!isSupported()) return@withContext Status.Unsupported
            lock.withLock {
                try {
                    sync(context.applicationContext, addIfMissing)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Watch face setup failed", e)
                    Status.Failed(describe(e))
                } catch (e: LinkageError) {
                    Log.e(TAG, "Watch Face Push unavailable", e)
                    Status.Unsupported
                }
            }
        }

    private suspend fun sync(context: Context, addIfMissing: Boolean): Status {
        val manager = WatchFacePushManagerFactory.createWatchFacePushManager(context)
        val response = manager.listWatchFaces()
        val installed = response.installedWatchFaceDetails
        val ours = installed.firstOrNull { it.packageName == PACKAGE }
        // Any other face in our slots is an older Moods face under a different name.
        val replaceable = ours ?: installed.firstOrNull()

        if (ours == null && response.remainingSlotCount > 0) {
            if (!addIfMissing) return Status.Failed("Watch face not installed")
            val bundled = bundled(context)
            val slot = ParcelFileDescriptor.open(bundled.apk, ParcelFileDescriptor.MODE_READ_ONLY).use {
                manager.addWatchFace(it, bundled.token)
            }
            Log.i(TAG, "Added ${slot.packageName} v${slot.versionCode} in slot ${slot.slotId}")
            return Status.Installed(slot.slotId, active = false)
        }
        if (replaceable == null) return Status.NoFreeSlot

        // Check before updating: isWatchFaceActive is unreliable right after updateWatchFace.
        val active = manager.isWatchFaceActive(replaceable.packageName)
        val bundled = bundled(context)
        if (ours != null && ours.versionCode >= bundled.versionCode) {
            return Status.Installed(ours.slotId, active)
        }
        val slot = ParcelFileDescriptor.open(bundled.apk, ParcelFileDescriptor.MODE_READ_ONLY).use {
            manager.updateWatchFace(replaceable.slotId, it, bundled.token)
        }
        Log.i(TAG, "Updated slot ${slot.slotId} to ${slot.packageName} v${slot.versionCode}")
        return Status.Installed(slot.slotId, active)
    }

    /** Returns true if the face is now active; false if the system refused. */
    suspend fun setActive(context: Context, slotId: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                WatchFacePushManagerFactory.createWatchFacePushManager(context.applicationContext)
                    .setWatchFaceAsActive(slotId)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "setWatchFaceAsActive failed", e)
                false
            }
        }
    }

    private fun bundled(context: Context): Bundled {
        val apk = File(context.cacheDir, APK_ASSET)
        context.assets.open(APK_ASSET).use { input ->
            apk.outputStream().use { input.copyTo(it) }
        }
        val token = context.assets.open(TOKEN_ASSET).bufferedReader().use { it.readText().trim() }
        val info = context.packageManager.getPackageArchiveInfo(
            apk.path, PackageManager.PackageInfoFlags.of(0)
        ) ?: error("Bundled watch face APK is unreadable")
        return Bundled(apk, info.longVersionCode, token)
    }

    private fun describe(e: Exception): String = when (e) {
        is AddWatchFaceException -> when (e.errorCode) {
            AddWatchFaceException.ERROR_SLOT_LIMIT_REACHED -> "No free watch face slot"
            AddWatchFaceException.ERROR_INVALID_VALIDATION_TOKEN -> "Watch face was rejected (token)"
            else -> "Couldn't install watch face (error ${e.errorCode})"
        }
        is UpdateWatchFaceException -> when (e.errorCode) {
            UpdateWatchFaceException.ERROR_INVALID_VALIDATION_TOKEN -> "Watch face update was rejected (token)"
            else -> "Couldn't update watch face (error ${e.errorCode})"
        }
        is WatchFacePushManager.ListWatchFacesException -> "Couldn't check watch faces"
        is SecurityException -> "Watch face permission missing"
        else -> e.message ?: "Couldn't set up watch face"
    }
}
