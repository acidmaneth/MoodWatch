package com.moodwatch.app

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.WearableRecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The user's saved Moods. Tapping one puts it on the face and returns to the watch face. */
class CollectionActivity : ComponentActivity() {

    private val adapter = Adapter()
    private var ids: List<Int> = emptyList()
    private var switchingTo: Int? = null
    private val thumbJobs = mutableMapOf<Int, Job>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collection)
        findViewById<WearableRecyclerView>(R.id.list).apply {
            isEdgeItemsCenteringEnabled = false
            layoutManager = LinearLayoutManager(this@CollectionActivity)
            adapter = this@CollectionActivity.adapter
            requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        ids = MoodCollection.ids(this)
        adapter.notifyDataSetChanged()
    }

    private fun switchTo(id: Int) {
        if (switchingTo != null) return
        if (id == MoodRepository.tokenId(this) && MoodRepository.currentImageBytes(this) != null) {
            finishAffinity()
            return
        }
        switchingTo = id
        adapter.notifyItemChanged(ids.indexOf(id) + 1)
        lifecycleScope.launch {
            try {
                MoodRepository.load(this@CollectionActivity, id)
                finishAffinity()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MoodNotFoundException) {
                toast(getString(R.string.collection_not_found, id))
            } catch (e: Exception) {
                toast(getString(R.string.collection_load_failed, id))
            } finally {
                switchingTo = null
                adapter.notifyItemChanged(ids.indexOf(id) + 1)
            }
        }
    }

    private fun confirmRemove(id: Int) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.collection_remove_confirm, id))
            .setPositiveButton(R.string.collection_remove) { _, _ ->
                MoodCollection.remove(this, id)
                val index = ids.indexOf(id)
                ids = MoodCollection.ids(this)
                if (index >= 0) adapter.notifyItemRemoved(index + 1)
                adapter.notifyItemChanged(0)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun bindThumb(holder: RowHolder, id: Int) {
        val cached = MoodCollection.cachedThumb(this, id)
        holder.show(id, cached)
        if (cached?.isFresh == true || thumbJobs[id]?.isActive == true) return
        thumbJobs[id] = lifecycleScope.launch {
            try {
                MoodCollection.fetchThumb(this@CollectionActivity, id)
                val index = ids.indexOf(id)
                if (index >= 0) adapter.notifyItemChanged(index + 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the stale thumbnail, or the placeholder, until the next visit.
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = ids.size + 1

        override fun getItemViewType(position: Int) = if (position == 0) HEADER else ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == HEADER) {
                object : RecyclerView.ViewHolder(
                    inflater.inflate(R.layout.item_collection_header, parent, false)
                ) {}
            } else {
                RowHolder(inflater.inflate(R.layout.item_collection, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position == 0) {
                holder.itemView.findViewById<TextView>(R.id.hint).text = getString(
                    if (ids.isEmpty()) R.string.collection_empty else R.string.collection_hint
                )
                return
            }
            val id = ids[position - 1]
            val row = holder as RowHolder
            bindThumb(row, id)
            row.itemView.setOnClickListener { switchTo(id) }
            row.itemView.setOnLongClickListener { confirmRemove(id); true }
        }
    }

    private inner class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.thumb)
        private val name: TextView = view.findViewById(R.id.name)
        private val traits: TextView = view.findViewById(R.id.traits)
        private val progress: ProgressBar = view.findViewById(R.id.progress)

        fun show(id: Int, cached: CollectionThumb?) {
            val current = id == MoodRepository.tokenId(this@CollectionActivity)
            name.text = if (current) getString(R.string.collection_row_current, id) else "#$id"
            traits.text = listOfNotNull(cached?.mood, cached?.expression).joinToString(" · ")
            traits.isVisible = traits.text.isNotEmpty()
            if (cached != null) {
                thumb.setImageBitmap(BitmapFactory.decodeByteArray(cached.image, 0, cached.image.size))
            } else {
                thumb.setImageDrawable(null)
            }
            itemView.isSelected = current
            progress.isVisible = switchingTo == id
        }
    }

    private companion object {
        const val HEADER = 0
        const val ROW = 1
    }
}
