package com.moodwatch.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var preview: ImageView
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var faceStatus: TextView
    private lateinit var faceButton: Button
    private lateinit var tokenInput: EditText
    private lateinit var saveButton: Button
    private lateinit var refreshButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var collectionButton: Button
    private lateinit var randomButton: Button
    private lateinit var collectToggle: Button
    private var loadJob: Job? = null
    private var faceJob: Job? = null
    private var activateJob: Job? = null
    private var faceSlotId: String? = null

    private val facePrefs by lazy { getSharedPreferences(FACE_PREFS, MODE_PRIVATE) }

    private val requestSetActive =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) setFaceActive() else showFaceHowTo(permissionDenied = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        title = findViewById(R.id.title)
        status = findViewById(R.id.status)
        faceStatus = findViewById(R.id.faceStatus)
        faceButton = findViewById(R.id.faceButton)
        tokenInput = findViewById(R.id.tokenInput)
        saveButton = findViewById(R.id.saveButton)
        refreshButton = findViewById(R.id.refreshButton)
        progress = findViewById(R.id.progress)
        collectionButton = findViewById(R.id.collectionButton)
        randomButton = findViewById(R.id.randomButton)
        collectToggle = findViewById(R.id.collectToggle)

        collectionButton.setOnClickListener {
            startActivity(Intent(this, CollectionActivity::class.java))
        }
        randomButton.setOnClickListener { pickRandom() }
        collectToggle.setOnClickListener { toggleCollected() }

        // Left empty: the Wear number pad appends to existing text rather than replacing it.
        tokenInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveAndRefresh()
                true
            } else {
                false
            }
        }
        saveButton.setOnClickListener { saveAndRefresh() }
        refreshButton.setOnClickListener { refresh() }

        MoodRefreshWorker.cancelLegacySchedules(this)
        MoodDriftScheduler.ensureScheduled(this)
        render()
        val baselineSince = MoodDriftScheduler.windowStart() - TimeUnit.DAYS.toMillis(1)
        if (MoodRepository.currentImageBytes(this) == null ||
            !MoodRepository.hasBaselineSince(this, baselineSince) ||
            MoodDriftScheduler.missedToday(this)
        ) {
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        syncFace()
    }

    /** Installs or updates the bundled watch face, then offers to make it active. */
    private fun syncFace() {
        if (activateJob?.isActive == true || faceJob?.isActive == true) return
        if (faceStatus.text.isNullOrEmpty()) showFace(getString(R.string.face_checking))
        faceJob = lifecycleScope.launch {
            when (val face = MoodFace.ensureInstalled(this@MainActivity)) {
                MoodFace.Status.Unsupported -> showFace(getString(R.string.face_unsupported))
                MoodFace.Status.NoFreeSlot ->
                    showFace(getString(R.string.face_no_slot), R.string.face_retry) { syncFace() }
                is MoodFace.Status.Failed ->
                    showFace(face.message, R.string.face_retry) { syncFace() }
                is MoodFace.Status.Installed -> {
                    faceSlotId = face.slotId
                    if (face.active) {
                        showFace(getString(R.string.face_active))
                    } else {
                        showFace(getString(R.string.face_installed), R.string.face_set_active) {
                            activateFace()
                        }
                        if (!facePrefs.getBoolean(KEY_AUTO_ACTIVATED, false)) {
                            facePrefs.edit { putBoolean(KEY_AUTO_ACTIVATED, true) }
                            activateFace()
                        }
                    }
                }
            }
        }
    }

    private fun activateFace() {
        val permission = MoodFace.SET_ACTIVE_PERMISSION
        when {
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED -> setFaceActive()
            // This permission can be denied only once; after that, only Settings can grant it.
            !facePrefs.getBoolean(KEY_PERMISSION_ASKED, false) ||
                shouldShowRequestPermissionRationale(permission) -> {
                facePrefs.edit { putBoolean(KEY_PERMISSION_ASKED, true) }
                requestSetActive.launch(permission)
            }
            else -> showFaceHowTo(permissionDenied = true)
        }
    }

    private fun setFaceActive() {
        val slotId = faceSlotId ?: return syncFace()
        activateJob = lifecycleScope.launch {
            if (MoodFace.setActive(this@MainActivity, slotId)) {
                showFace(getString(R.string.face_active))
            } else {
                showFaceHowTo(permissionDenied = false)
            }
        }
    }

    private fun showFaceHowTo(permissionDenied: Boolean) {
        val text = getString(R.string.face_installed) + "\n" + getString(R.string.face_how_to)
        if (permissionDenied) {
            showFace(text, R.string.face_open_settings) { openAppSettings() }
        } else {
            showFace(text)
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        } catch (e: ActivityNotFoundException) {
            showFaceHowTo(permissionDenied = false)
        }
    }

    private fun showFace(text: String, actionLabel: Int? = null, action: (() -> Unit)? = null) {
        faceStatus.text = text
        faceButton.isVisible = actionLabel != null
        if (actionLabel != null) {
            faceButton.setText(actionLabel)
            faceButton.setOnClickListener { action?.invoke() }
        }
    }

    private fun saveAndRefresh() {
        val id = tokenInput.text.toString().trim().toIntOrNull()
        if (id == null || id < 0) {
            status.text = "Enter a Mood ID number"
            return
        }
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(tokenInput.windowToken, 0)
        startLoad(id) { MoodRepository.load(this, id) }
    }

    private fun refresh() {
        startLoad(MoodRepository.tokenId(this)) { MoodRepository.refresh(this) }
    }

    /** Some IDs may be missing (e.g. burned), so a miss just rolls again. */
    private fun pickRandom() {
        startLoad(id = null) {
            val supply = MoodRepository.totalSupply(this)
            val current = MoodRepository.tokenId(this)
            var miss: MoodNotFoundException? = null
            repeat(RANDOM_TRIES) {
                val id = (1..supply).random().let { if (it == current) it % supply + 1 else it }
                try {
                    MoodRepository.load(this, id)
                    return@startLoad
                } catch (e: MoodNotFoundException) {
                    miss = e
                }
            }
            throw miss!!
        }
    }

    private fun toggleCollected() {
        val id = MoodRepository.tokenId(this)
        if (MoodCollection.contains(this, id)) {
            MoodCollection.remove(this, id)
        } else {
            MoodCollection.add(this, id)
            lifecycleScope.launch { MoodCollection.seedFromCurrent(this@MainActivity) }
        }
        renderCollection()
    }

    /**
     * The current Mood stays on screen until [block] has saved the new one. [id] is the Mood
     * being loaded, or null for a random pick.
     */
    private fun startLoad(id: Int?, block: suspend () -> Unit) {
        loadJob?.cancel()
        progress.isVisible = true
        status.text = if (id == null) getString(R.string.picking_random) else "Loading Moods #$id…"
        loadJob = lifecycleScope.launch {
            try {
                block()
                render()
                tokenInput.text.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MoodNotFoundException) {
                status.text = if (id == null) "No Mood found, try again" else "Mood #$id not found"
                tokenInput.text.clear()
            } catch (e: Exception) {
                status.text = e.message ?: "Could not load the Mood"
            } finally {
                if (loadJob == coroutineContext.job) progress.isVisible = false
            }
        }
    }

    private fun renderCollection() {
        val id = MoodRepository.tokenId(this)
        collectionButton.text = getString(R.string.collection_button, MoodCollection.ids(this).size)
        collectToggle.setText(
            if (MoodCollection.contains(this, id)) R.string.collection_remove_current
            else R.string.collection_add
        )
        collectToggle.isEnabled = MoodRepository.currentImageBytes(this) != null
    }

    private fun render() {
        renderCollection()
        val bytes = MoodRepository.currentImageBytes(this)
        if (bytes != null) {
            preview.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        } else {
            preview.setImageResource(R.drawable.mood_preview)
        }

        val info = MoodRepository.lastInfo(this)
        if (info == null || info.tokenId != MoodRepository.tokenId(this)) {
            title.text = "Moods #${MoodRepository.tokenId(this)}"
            status.text = "Not loaded yet"
            return
        }
        title.text = info.name
        val updated = DateUtils.getRelativeTimeSpanString(info.updatedAt)
        status.text = listOfNotNull(info.expression, info.mood, "Updated $updated")
            .joinToString("\n")
    }

    private companion object {
        const val FACE_PREFS = "face"
        const val KEY_AUTO_ACTIVATED = "auto_activated"
        const val KEY_PERMISSION_ASKED = "set_active_permission_asked"
        const val RANDOM_TRIES = 3
    }
}
