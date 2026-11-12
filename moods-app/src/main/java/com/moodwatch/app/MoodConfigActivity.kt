package com.moodwatch.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Opened by the watch face editor when "Moods NFT" is chosen for a slot, so the Mood ID can be
 * picked right there. The editor drops the data source unless this returns RESULT_OK.
 */
class MoodConfigActivity : ComponentActivity() {

    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var tokenInput: EditText
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        tokenInput = findViewById(R.id.tokenInput)
        saveButton = findViewById(R.id.saveButton)

        // Swiping away keeps Moods in the slot with the current ID.
        setResult(RESULT_OK)

        val current = MoodRepository.tokenId(this)
        tokenInput.setText(current.toString())
        status.text = "Now showing #$current"
        MoodRepository.currentImageBytes(this)?.let {
            preview.setImageBitmap(BitmapFactory.decodeByteArray(it, 0, it.size))
        } ?: preview.setImageResource(R.drawable.mood_preview)

        tokenInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save()
                true
            } else {
                false
            }
        }
        saveButton.setOnClickListener { save() }
    }

    private fun save() {
        val id = tokenInput.text.toString().trim().toIntOrNull()
        if (id == null || id < 0) {
            status.text = "Enter a Mood ID number"
            return
        }
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(tokenInput.windowToken, 0)

        val previous = MoodRepository.tokenId(this)
        if (id == previous && MoodRepository.currentImageBytes(this) != null) {
            finish()
            return
        }

        MoodRepository.setTokenId(this, id)
        saveButton.isEnabled = false
        status.text = "Loading Moods #$id…"
        lifecycleScope.launch {
            try {
                withTimeout(LOAD_TIMEOUT_MS) { MoodRepository.refresh(this@MoodConfigActivity) }
                finish()
            } catch (e: MoodNotFoundException) {
                MoodRepository.setTokenId(this@MoodConfigActivity, previous)
                status.text = "Mood #$id not found"
                saveButton.isEnabled = true
            } catch (e: Exception) {
                // Slow or no network yet: keep the new ID and let the worker load it.
                MoodRefreshWorker.refreshNow(this@MoodConfigActivity, replace = true)
                finish()
            }
        }
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 45_000L
    }
}
