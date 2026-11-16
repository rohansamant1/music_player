package com.example.musicplayer

import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity : AppCompatActivity() {

    private lateinit var myRecyclerView: RecyclerView
    private lateinit var artistRecyclerView: RecyclerView
    private lateinit var myAdapter: MyAdapter
    private lateinit var artistAdapter: ArtistAdapter
    private lateinit var searchLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var artistSearchAuto: AutoCompleteTextView
    private lateinit var tvSongsHeader: TextView

    // Bottom Mini Player views
    private lateinit var bottomPlayerBar: CardView
    private lateinit var bottomSongImage: ImageView
    private lateinit var bottomSongTitle: TextView
    private lateinit var bottomArtistName: TextView
    private lateinit var bottomBtnPlayPause: ImageButton
    private lateinit var bottomBtnStop: ImageButton
    private lateinit var bottomSeekBar: SeekBar

    // Centralized MediaPlayer state
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingTrack: Data? = null
    private var currentPlayingPosition: Int = -1
    private var isPaused: Boolean = false
    private var isUserTrackingSeekBar: Boolean = false

    private val progressHandler = Handler(Looper.getMainLooper())
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                if (!isUserTrackingSeekBar && player.isPlaying) {
                    val duration = player.duration
                    if (duration > 0) {
                        val progress = (player.currentPosition * 100) / duration
                        bottomSeekBar.progress = progress
                    }
                }
                progressHandler.postDelayed(this, 300)
            }
        }
    }

    private val apiInterface: ApiInterface by lazy {
        Retrofit.Builder()
            .baseUrl("https://deezerdevs-deezer.p.rapidapi.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiInterface::class.java)
    }

    // Dynamic artists list populated entirely from API responses (no hard-coded artist list)
    private val dynamicArtists = mutableListOf<ArtistItem>()
    private val autoArtistNames = mutableListOf<String>()
    private lateinit var searchAdapter: ArrayAdapter<String>

    // Debounce runnable for live search autocomplete
    private var liveSearchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        initViews()
        setupArtistsRecyclerView()
        setupAutoCompleteSearch()
        setupBottomPlayerBar()

        // Initialize empty songs adapter
        myAdapter = MyAdapter(
            context = this,
            dataList = emptyList(),
            onPlayRequested = { track, position, tint ->
                handlePlayRequest(track, position, tint)
            },
            onPauseRequested = { _, _ ->
                pausePlayback()
            }
        )
        myRecyclerView.layoutManager = LinearLayoutManager(this)
        myRecyclerView.adapter = myAdapter

        // Load trending artists dynamically from API (no hardcoded artists)
        loadTrendingArtists()
    }

    private fun initViews() {
        myRecyclerView = findViewById(R.id.recyclerView)
        artistRecyclerView = findViewById(R.id.artistRecyclerView)
        searchLayout = findViewById(R.id.searchLayout)
        artistSearchAuto = findViewById(R.id.artistSearchAuto)
        tvSongsHeader = findViewById(R.id.tvSongsHeader)

        bottomPlayerBar = findViewById(R.id.bottomPlayerBar)
        bottomSongImage = findViewById(R.id.bottomSongImage)
        bottomSongTitle = findViewById(R.id.bottomSongTitle)
        bottomArtistName = findViewById(R.id.bottomArtistName)
        bottomBtnPlayPause = findViewById(R.id.bottomBtnPlayPause)
        bottomBtnStop = findViewById(R.id.bottomBtnStop)
        bottomSeekBar = findViewById(R.id.bottomSeekBar)
    }

    private fun setupArtistsRecyclerView() {
        artistAdapter = ArtistAdapter(this, dynamicArtists) { selectedArtist ->
            artistSearchAuto.setText("")
            artistSearchAuto.clearFocus()
            fetchSongsForArtist(selectedArtist.name)
        }
        artistRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        artistRecyclerView.adapter = artistAdapter
    }

    private fun setupAutoCompleteSearch() {
        searchAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, autoArtistNames)
        artistSearchAuto.setAdapter(searchAdapter)
        artistSearchAuto.threshold = 1

        val triggerSearch = {
            val query = artistSearchAuto.text.toString().trim()
            if (query.isNotEmpty()) {
                artistAdapter.selectedArtistName = query
                fetchSongsForArtist(query)
                artistSearchAuto.dismissDropDown()
                artistSearchAuto.clearFocus()
            }
        }

        artistSearchAuto.setOnItemClickListener { _, _, position, _ ->
            val selectedName = searchAdapter.getItem(position) ?: return@setOnItemClickListener
            artistAdapter.selectedArtistName = selectedName
            fetchSongsForArtist(selectedName)
            artistSearchAuto.clearFocus()
        }

        artistSearchAuto.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                triggerSearch()
                true
            } else {
                false
            }
        }

        // Real-time dynamic autocomplete query as user types
        artistSearchAuto.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim() ?: ""
                liveSearchRunnable?.let { progressHandler.removeCallbacks(it) }
                if (query.length >= 2) {
                    val runnable = Runnable { fetchAutoCompleteSuggestions(query) }
                    liveSearchRunnable = runnable
                    progressHandler.postDelayed(runnable, 350)
                }
            }
        })

        // Tapping the search icon at the right end of the search input triggers search
        searchLayout.setEndIconOnClickListener {
            triggerSearch()
        }
    }

    private fun loadTrendingArtists() {
        tvSongsHeader.text = "Loading..."
        // Discover initial artists dynamically by querying the API
        apiInterface.getData("top").enqueue(object : Callback<MyData?> {
            override fun onResponse(call: Call<MyData?>, response: Response<MyData?>) {
                if (response.isSuccessful) {
                    val tracks = response.body()?.data ?: emptyList()
                    val discoveredArtists = tracks
                        .map { ArtistItem(it.artist.name, it.artist.picture_medium) }
                        .distinctBy { it.name.lowercase() }

                    artistAdapter.setArtists(discoveredArtists)

                    discoveredArtists.forEach { artist ->
                        if (!autoArtistNames.contains(artist.name)) {
                            autoArtistNames.add(artist.name)
                        }
                    }
                    searchAdapter.notifyDataSetChanged()

                    val firstArtist = discoveredArtists.firstOrNull()
                    if (firstArtist != null) {
                        artistAdapter.selectedArtistName = firstArtist.name
                        fetchSongsForArtist(firstArtist.name)
                    }
                }
            }

            override fun onFailure(call: Call<MyData?>, t: Throwable) {
                Log.e("MainActivity", "Failed to load initial artists: ${t.message}")
            }
        })
    }

    private fun fetchAutoCompleteSuggestions(prefix: String) {
        apiInterface.getData(prefix).enqueue(object : Callback<MyData?> {
            override fun onResponse(call: Call<MyData?>, response: Response<MyData?>) {
                if (response.isSuccessful) {
                    val tracks = response.body()?.data ?: return
                    var updated = false
                    tracks.forEach { track ->
                        val name = track.artist.name
                        if (!autoArtistNames.any { it.equals(name, ignoreCase = true) }) {
                            autoArtistNames.add(name)
                            updated = true
                        }
                    }
                    if (updated) {
                        searchAdapter.notifyDataSetChanged()
                    }
                }
            }

            override fun onFailure(call: Call<MyData?>, t: Throwable) {}
        })
    }

    private fun setupBottomPlayerBar() {
        bottomBtnPlayPause.setOnClickListener {
            if (mediaPlayer?.isPlaying == true) {
                pausePlayback()
            } else if (isPaused && mediaPlayer != null) {
                resumePlayback()
            } else if (currentPlayingTrack != null && currentPlayingPosition != -1) {
                val tint = myAdapter.getTintColorForTrack(currentPlayingTrack!!.id)
                startPlayingTrack(currentPlayingTrack!!, currentPlayingPosition, tint)
            }
        }

        bottomBtnStop.setOnClickListener {
            stopPlayback()
        }

        bottomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Seek preview if needed
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekBar = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekBar = false
                mediaPlayer?.let { player ->
                    val duration = player.duration
                    if (duration > 0 && seekBar != null) {
                        val newPosition = (seekBar.progress * duration) / 100
                        player.seekTo(newPosition)
                    }
                }
            }
        })
    }

    private fun fetchSongsForArtist(artistName: String) {
        tvSongsHeader.text = "$artistName's Songs"

        apiInterface.getData(artistName).enqueue(object : Callback<MyData?> {
            override fun onResponse(call: Call<MyData?>, response: Response<MyData?>) {
                if (response.isSuccessful) {
                    val tracks = response.body()?.data ?: emptyList()
                    myAdapter.updateData(tracks)

                    // If a custom or new artist was searched, extract their real name & picture from the API results
                    // and automatically pin them to the horizontal Popular Artists list
                    val firstTrack = tracks.firstOrNull()
                    if (firstTrack != null) {
                        val realArtistName = firstTrack.artist.name
                        val realArtistImg = firstTrack.artist.picture_medium
                        val newItem = ArtistItem(realArtistName, realArtistImg)
                        artistAdapter.addArtistIfMissing(newItem)
                        artistRecyclerView.scrollToPosition(0)
                    }

                    Log.d("MainActivity", "Loaded ${tracks.size} tracks for $artistName")
                } else {
                    Log.e("MainActivity", "Failed to load songs: ${response.code()}")
                }
            }

            override fun onFailure(call: Call<MyData?>, t: Throwable) {
                Log.e("MainActivity", "Error fetching songs: ${t.message}")
            }
        })
    }

    private fun handlePlayRequest(track: Data, position: Int, tint: Int) {
        if (currentPlayingPosition == position && currentPlayingTrack?.id == track.id) {
            if (isPaused) {
                resumePlayback()
            } else if (mediaPlayer?.isPlaying != true) {
                startPlayingTrack(track, position, tint)
            }
        } else {
            // New song clicked: stop previous and play new from start
            startPlayingTrack(track, position, tint)
        }
    }

    private fun startPlayingTrack(track: Data, position: Int, tint: Int) {
        // Stop any running playback
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        isPaused = false

        currentPlayingTrack = track
        currentPlayingPosition = position

        myAdapter.setPlaybackState(position, playing = true)
        updateBottomPlayerBar(track, isPlaying = true, tint = tint)

        val player = MediaPlayer()
        mediaPlayer = player

        try {
            player.setDataSource(track.preview)
            player.prepareAsync()
            player.setOnPreparedListener { mp ->
                if (currentPlayingTrack?.id == track.id) {
                    mp.start()
                    progressHandler.post(updateProgressRunnable)
                    updateBottomPlayerBar(track, isPlaying = true, tint = tint)
                }
            }
            player.setOnCompletionListener {
                onTrackCompleted()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopPlayback()
        }
    }

    private fun pausePlayback() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                isPaused = true
                myAdapter.setPlaybackState(currentPlayingPosition, playing = false)
                bottomBtnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            }
        }
    }

    private fun resumePlayback() {
        mediaPlayer?.let { player ->
            if (isPaused) {
                player.start()
                isPaused = false
                myAdapter.setPlaybackState(currentPlayingPosition, playing = true)
                bottomBtnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                progressHandler.post(updateProgressRunnable)
            }
        }
    }

    private fun stopPlayback() {
        progressHandler.removeCallbacks(updateProgressRunnable)
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        val prevPos = currentPlayingPosition
        currentPlayingTrack = null
        currentPlayingPosition = -1
        isPaused = false

        if (prevPos != -1) {
            myAdapter.setPlaybackState(-1, playing = false)
        }

        bottomSeekBar.progress = 0
        bottomPlayerBar.visibility = View.GONE
    }

    private fun onTrackCompleted() {
        progressHandler.removeCallbacks(updateProgressRunnable)
        val finishedPos = currentPlayingPosition
        currentPlayingTrack = null
        currentPlayingPosition = -1
        isPaused = false

        if (finishedPos != -1) {
            myAdapter.setPlaybackState(-1, playing = false)
        }

        bottomSeekBar.progress = 0
        bottomBtnPlayPause.setImageResource(android.R.drawable.ic_media_play)
    }

    private fun updateBottomPlayerBar(track: Data, isPlaying: Boolean, tint: Int) {
        bottomPlayerBar.visibility = View.VISIBLE
        bottomSongTitle.text = track.title
        bottomArtistName.text = track.artist.name

        // Blend the song's tint color over white with 100% opacity (alpha = 255)
        // so the bottom bar is completely solid and content underneath cannot show through
        val r = Color.red(tint)
        val g = Color.green(tint)
        val b = Color.blue(tint)
        val solidTint = Color.rgb(
            (255 * 0.55 + r * 0.45).toInt().coerceIn(0, 255),
            (255 * 0.55 + g * 0.45).toInt().coerceIn(0, 255),
            (255 * 0.55 + b * 0.45).toInt().coerceIn(0, 255)
        )
        bottomPlayerBar.setCardBackgroundColor(solidTint)

        // Tint seekbar thumb & progress bar with a deeper tone of the cover color
        val accentColor = Color.rgb(
            (r * 0.75).toInt().coerceAtLeast(30),
            (g * 0.75).toInt().coerceAtLeast(30),
            (b * 0.75).toInt().coerceAtLeast(30)
        )
        bottomSeekBar.thumbTintList = ColorStateList.valueOf(accentColor)
        bottomSeekBar.progressTintList = ColorStateList.valueOf(accentColor)

        if (track.album.cover_small.isNotEmpty()) {
            Picasso.get().load(track.album.cover_small).into(bottomSongImage)
        } else {
            bottomSongImage.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        bottomBtnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacks(updateProgressRunnable)
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
