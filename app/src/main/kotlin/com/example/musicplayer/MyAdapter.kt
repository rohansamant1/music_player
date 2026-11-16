package com.example.musicplayer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import com.squareup.picasso.Target
import kotlin.concurrent.thread

class MyAdapter(
    private val context: Activity,
    var dataList: List<Data>,
    private val onPlayRequested: (Data, Int, Int) -> Unit,
    private val onPauseRequested: (Data, Int) -> Unit
) : RecyclerView.Adapter<MyAdapter.MyViewHolder>() {

    var currentPlayingPosition: Int = -1
    var isPlaying: Boolean = false

    // Cache extracted tint color per track ID
    private val tintColors = mutableMapOf<Long, Int>()

    fun updateData(newList: List<Data>) {
        dataList = newList
        currentPlayingPosition = -1
        isPlaying = false
        notifyDataSetChanged()
    }

    fun setPlaybackState(position: Int, playing: Boolean) {
        val prevPos = currentPlayingPosition
        currentPlayingPosition = position
        isPlaying = playing

        if (prevPos != -1 && prevPos != position) {
            notifyItemChanged(prevPos)
        }
        if (position != -1) {
            notifyItemChanged(position)
        }
    }

    fun getTintColorForTrack(trackId: Long): Int {
        return tintColors[trackId] ?: Color.parseColor("#E0E0E0")
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val itemView = LayoutInflater.from(context).inflate(R.layout.each_item, parent, false)
        return MyViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        val currentData = dataList[position]
        val trackId = currentData.id
        holder.title.text = currentData.title

        holder.imageTarget?.let { Picasso.get().cancelRequest(it) }

        val target = object : Target {
            override fun onBitmapLoaded(bitmap: Bitmap?, from: Picasso.LoadedFrom?) {
                if (bitmap != null) {
                    holder.image.setImageBitmap(bitmap)
                    if (!tintColors.containsKey(trackId)) {
                        thread {
                            val palette = Palette.from(bitmap).generate()
                            val swatch = palette.dominantSwatch
                                ?: palette.lightMutedSwatch
                                ?: palette.mutedSwatch
                            val baseColor = swatch?.rgb ?: Color.LTGRAY
                            val tinted = Color.argb(
                                85,
                                Color.red(baseColor),
                                Color.green(baseColor),
                                Color.blue(baseColor)
                            )
                            context.runOnUiThread {
                                tintColors[trackId] = tinted
                                val currentPos = holder.bindingAdapterPosition
                                if (currentPos != RecyclerView.NO_POSITION && currentPos == currentPlayingPosition) {
                                    holder.cardView.setCardBackgroundColor(tinted)
                                }
                            }
                        }
                    }
                }
            }

            override fun onBitmapFailed(e: Exception?, errorDrawable: Drawable?) {
                holder.image.setImageDrawable(errorDrawable)
            }

            override fun onPrepareLoad(placeHolderDrawable: Drawable?) {
                holder.image.setImageDrawable(placeHolderDrawable)
            }
        }

        holder.imageTarget = target
        Picasso.get().load(currentData.album.cover_medium).into(target)

        // Highlight card only if it's currently active/playing
        val isThisActive = (position == currentPlayingPosition)
        if (isThisActive) {
            val tint = tintColors[trackId] ?: Color.parseColor("#E0E0E0")
            holder.cardView.setCardBackgroundColor(tint)
        } else {
            holder.cardView.setCardBackgroundColor(Color.WHITE)
        }

        // Play button click
        holder.play.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos == RecyclerView.NO_POSITION) return@setOnClickListener
            val tint = tintColors[trackId] ?: Color.parseColor("#E0E0E0")
            onPlayRequested(currentData, adapterPos, tint)
        }

        // Pause button click
        holder.pause.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos == RecyclerView.NO_POSITION) return@setOnClickListener
            onPauseRequested(currentData, adapterPos)
        }
    }

    override fun getItemCount(): Int = dataList.size

    class MyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardView: CardView = itemView.findViewById(R.id.cardView)
        val image: ImageView = itemView.findViewById(R.id.musicImage)
        val title: TextView = itemView.findViewById(R.id.musicTitle)
        val play: ImageButton = itemView.findViewById(R.id.btnPlay)
        val pause: ImageButton = itemView.findViewById(R.id.btnPause)
        var imageTarget: Target? = null
    }
}
