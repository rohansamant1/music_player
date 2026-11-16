package com.example.musicplayer

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso

class ArtistAdapter(
    private val context: Context,
    private val artists: MutableList<ArtistItem>,
    private val onArtistClick: (ArtistItem) -> Unit
) : RecyclerView.Adapter<ArtistAdapter.ArtistViewHolder>() {

    var selectedArtistName: String = ""
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun setArtists(newArtists: List<ArtistItem>) {
        artists.clear()
        artists.addAll(newArtists)
        notifyDataSetChanged()
    }

    fun addArtistIfMissing(artistItem: ArtistItem) {
        val existingIndex = artists.indexOfFirst { it.name.equals(artistItem.name, ignoreCase = true) }
        if (existingIndex == -1) {
            artists.add(0, artistItem)
            notifyItemInserted(0)
        } else if (artistItem.imageUrl.isNotEmpty() && artists[existingIndex].imageUrl.isEmpty()) {
            artists[existingIndex] = artistItem
            notifyItemChanged(existingIndex)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.artist_item, parent, false)
        return ArtistViewHolder(view)
    }

    override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
        val artist = artists[position]
        holder.artistName.text = artist.name

        if (artist.imageUrl.isNotEmpty()) {
            Picasso.get()
                .load(artist.imageUrl)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_report_image)
                .into(holder.artistImage)
        } else {
            holder.artistImage.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        val isSelected = artist.name.equals(selectedArtistName, ignoreCase = true)
        if (isSelected) {
            holder.artistCard.setCardBackgroundColor(Color.parseColor("#4A90E2"))
            holder.artistName.setTextColor(Color.parseColor("#4A90E2"))
        } else {
            holder.artistCard.setCardBackgroundColor(Color.WHITE)
            holder.artistName.setTextColor(Color.BLACK)
        }

        holder.itemView.setOnClickListener {
            selectedArtistName = artist.name
            onArtistClick(artist)
        }
    }

    override fun getItemCount(): Int = artists.size

    class ArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val artistCard: CardView = itemView.findViewById(R.id.artistCard)
        val artistImage: ImageView = itemView.findViewById(R.id.artistImage)
        val artistName: TextView = itemView.findViewById(R.id.artistName)
    }
}
