package com.simplemobiletools.musicplayer.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.AudioManager.*
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.MediaStore.Audio
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Size
import android.view.KeyEvent
import androidx.media.session.MediaButtonReceiver
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.commons.helpers.isOreoPlus
import com.simplemobiletools.commons.helpers.isQPlus
import com.simplemobiletools.commons.helpers.isSPlus
import com.simplemobiletools.musicplayer.R
import com.simplemobiletools.musicplayer.databases.SongsDatabase
import com.simplemobiletools.musicplayer.extensions.*
import com.simplemobiletools.musicplayer.helpers.*
import com.simplemobiletools.musicplayer.helpers.NotificationHelper.Companion.NOTIFICATION_ID
import com.simplemobiletools.musicplayer.inlines.indexOfFirstOrNull
import com.simplemobiletools.musicplayer.models.Events
import com.simplemobiletools.musicplayer.models.QueueItem
import com.simplemobiletools.musicplayer.models.Track
import com.simplemobiletools.musicplayer.receivers.HeadsetPlugReceiver
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

class MusicService : Service(), MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener, OnAudioFocusChangeListener {
    companion object {
        private const val PROGRESS_UPDATE_INTERVAL = 1000L
        private const val MAX_CLICK_DURATION = 700L
        private const val FAST_FORWARD_SKIP_MS = 10000

        var mCurrTrack: Track? = null
        var mTracks = ArrayList<Track>()
        var mPlayer: MediaPlayer? = null
        var mEqualizer: Equalizer? = null
        private var mCurrTrackCover: Bitmap? = null
        private var mHeadsetPlaceholder: Bitmap? = null
        private var mHeadsetPlugReceiver = HeadsetPlugReceiver()
        private var mProgressHandler = Handler()
        private var mSleepTimer: CountDownTimer? = null
        private var mAudioManager: AudioManager? = null
        private var mCoverArtHeight = 0
        private var mRetriedTrackCount = 0
        private var mPlaybackSpeed = 1f
        private var mOreoFocusHandler: OreoAudioFocusHandler? = null

        private var mWasPlayingAtFocusLost = false
        private var mPlayOnPrepare = true
        private var mIsThirdPartyIntent = false
        private var mIntentUri: Uri? = null
        private var mMediaSession: MediaSessionCompat? = null
        var mIsServiceInitialized = false
        private var mPrevAudioFocusState = 0
        private var mSetProgressOnPrepare = 0
        private const val mMediaSessionActions =
            PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_PLAY_PAUSE

        fun isPlaying(): Boolean {
            return try {
                mPlayer?.isPlaying == true
            } catch (e: Exception) {
                false
            }
        }
    }

    private var mClicksCnt = 0
    private val mRemoteControlHandler = Handler()
    private val mRunnable = Runnable {
        if (mClicksCnt == 0) {
            return@Runnable
        }

        when (mClicksCnt) {
            1 -> handlePlayPause()
            2 -> handleNext()
            else -> handlePrevious()
        }
        mClicksCnt = 0
    }

    private val notificationHandler = Handler()
    private var notificationHelper: NotificationHelper? = null

    override fun onCreate() {
        super.onCreate()
        mCoverArtHeight = resources.getDimension(R.dimen.top_art_height).toInt()
        createMediaSession()

        notificationHelper = NotificationHelper.createInstance(context = this, mMediaSession!!)
        startForegroundAndNotify()

        mAudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (isOreoPlus()) {
            mOreoFocusHandler = OreoAudioFocusHandler(application)
        }

        if (!isQPlus() && !hasPermission(getPermissionToRequest())) {
            EventBus.getDefault().post(Events.NoStoragePermission())
        }
    }

    private fun createMediaSession() {
        mMediaSession = MediaSessionCompat(this, "MusicService")
        mMediaSession!!.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)
        val mediaSessionCallback = MediaSessionCallback(this)
        mMediaSession!!.setCallback(mediaSessionCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyPlayer()
        mMediaSession?.isActive = false
        mMediaSession = null
        mEqualizer?.release()
        mEqualizer = null
        mSleepTimer?.cancel()
        config.sleepInTS = 0L
        SongsDatabase.destroyInstance()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (!isQPlus() && !hasPermission(getPermissionToRequest())) {
            return START_NOT_STICKY
        }

        notifyFocusGained()

        val action = intent.action
        when (action) {
            INIT -> handleInit(intent)
            INIT_PATH -> handleInitPath(intent)
            INIT_QUEUE -> handleInitQueue()
            PREVIOUS -> handlePrevious()
            PAUSE -> pauseTrack()
            PLAYPAUSE -> handlePlayPause()
            NEXT -> handleNext()
            PLAY_TRACK -> playTrack(intent)
            EDIT -> handleEdit(intent)
            FINISH -> handleFinish()
            FINISH_IF_NOT_PLAYING -> finishIfNotPlaying()
            DISMISS -> handleDismiss()
            REFRESH_LIST -> handleRefreshList()
            UPDATE_NEXT_TRACK -> broadcastNextTrackChange()
            SET_PROGRESS -> handleSetProgress(intent)
            SKIP_BACKWARD -> skip(false)
            SKIP_FORWARD -> skip(true)
            START_SLEEP_TIMER -> startSleepTimer()
            STOP_SLEEP_TIMER -> stopSleepTimer()
            BROADCAST_STATUS -> broadcastPlayerStatus()
            SET_PLAYBACK_SPEED -> setPlaybackSpeed()
            UPDATE_QUEUE_SIZE -> updateQueueSize()
        }

        MediaButtonReceiver.handleIntent(mMediaSession!!, intent)
        if (action != DISMISS && action != FINISH) {
            startForegroundAndNotify()
        }
        return START_NOT_STICKY
    }

    private fun notifyFocusGained() {
        mWasPlayingAtFocusLost = false
        mPrevAudioFocusState = AUDIOFOCUS_GAIN
    }

    private fun initService(intent: Intent?) {
        mTracks.clear()
        mCurrTrack = null
        if (mIsThirdPartyIntent && mIntentUri != null) {
            val path = getRealPathFromURI(mIntentUri!!) ?: ""
            val track = RoomHelper(this).getTrackFromPath(path)
            if (track != null) {
                if (track.title.isEmpty()) {
                    track.title = mIntentUri?.toString()?.getFilenameFromPath() ?: ""
                }

                if (track.duration == 0) {
                    try {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(this, mIntentUri)
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toInt() / 1000f
                        track.duration = duration.roundToInt()
                    } catch (ignored: Exception) {
                    }
                }

                mTracks.add(track)
            }
        } else {
            mTracks = getQueuedTracks()

            var wantedTrackId = intent?.getLongExtra(TRACK_ID, -1L) ?: -1L
            if (wantedTrackId == -1L) {
                val currentQueueItem = getNextQueueItem()
                wantedTrackId = currentQueueItem.trackId
                mSetProgressOnPrepare = currentQueueItem.lastPosition
            }

            // use an old school loop to avoid ConcurrentModificationException
            for (i in 0 until mTracks.size) {
                val track = mTracks[i]
                if (track.mediaStoreId == wantedTrackId) {
                    mCurrTrack = track
                    break
                }
            }

            checkTrackOrder()
        }

        mWasPlayingAtFocusLost = false
        initMediaPlayerIfNeeded()
        startForegroundAndNotify()
        mIsServiceInitialized = true
    }

    private fun handleInit(intent: Intent? = null) {
        mIsThirdPartyIntent = false
        ensureBackgroundThread {
            initService(intent)

            val wantedTrackId = mCurrTrack?.mediaStoreId ?: -1L
            mPlayOnPrepare = true
            setTrack(wantedTrackId)
        }
    }

    private fun handleInitPath(intent: Intent) {
        mIsThirdPartyIntent = true
        if (mIntentUri != intent.data) {
            mIntentUri = intent.data
            initService(intent)
            initTracks()
        } else {
            updateUI()
        }
    }

    private fun handleInitQueue() {
        ensureBackgroundThread {
            val unsortedTracks = getQueuedTracks()

            mTracks.clear()
            val queuedItems = queueDAO.getAll()
            queuedItems.forEach { queueItem ->
                unsortedTracks.firstOrNull { it.mediaStoreId == queueItem.trackId }?.apply {
                    mTracks.add(this)
                }
            }

            checkTrackOrder()
            val currentQueueItem = queuedItems.firstOrNull { it.isCurrent } ?: queuedItems.firstOrNull()
            if (currentQueueItem != null) {
                mCurrTrack = mTracks.firstOrNull { it.mediaStoreId == currentQueueItem.trackId } ?: return@ensureBackgroundThread
                mPlayOnPrepare = false
                mSetProgressOnPrepare = currentQueueItem.lastPosition
                setTrack(mCurrTrack!!.mediaStoreId)
            }
        }
    }

    fun handlePrevious() {
        mPlayOnPrepare = true
        playPreviousTrack()
    }

    private fun handlePlayPause() {
        mPlayOnPrepare = true
        if (isPlaying()) {
            pauseTrack()
        } else {
            resumeTrack()
        }
    }

    fun handleNext() {
        mPlayOnPrepare = true
        setupNextTrack()
    }

    private fun handleEdit(intent: Intent) {
        mCurrTrack = intent.getSerializableExtra(EDITED_TRACK) as Track
        trackChanged()
    }

    private fun finishIfNotPlaying() {
        if (!isPlaying()) {
            handleFinish()
        }
    }

    private fun handleFinish() {
        broadcastTrackProgress(0)
        stopSelf()
    }

    fun handleDismiss() {
        if (isPlaying()) {
            pauseTrack(false)
        }
        stopForegroundAndNotification()
    }

    private fun handleRefreshList() {
        ensureBackgroundThread {
            mTracks = getQueuedTracks()
            checkTrackOrder()
            EventBus.getDefault().post(Events.QueueUpdated(mTracks))
            broadcastNextTrackChange()
        }
    }

    private fun handleSetProgress(intent: Intent) {
        if (mPlayer != null) {
            val progress = intent.getIntExtra(PROGRESS, getPosition()!! / 1000)
            updateProgress(progress)
        }
    }

    private fun setupTrack() {
        if (mIsThirdPartyIntent) {
            initMediaPlayerIfNeeded()

            try {
                mPlayer!!.apply {
                    reset()
                    setDataSource(applicationContext, mIntentUri!!)
                    prepare()
                    start()
                }
                requestAudioFocus()

                val track = mTracks.first()
                mTracks.clear()
                mTracks.add(track)
                mCurrTrack = track
                updateUI()
            } catch (ignored: Exception) {
            }
        } else {
            mPlayOnPrepare = false
            setupNextTrack()
        }
    }

    private fun initTracks() {
        if (mCurrTrack == null) {
            setupTrack()
        }
        updateUI()
    }

    @Synchronized
    private fun updateUI() {
        ensureBackgroundThread {
            if (mPlayer != null) {
                EventBus.getDefault().post(Events.QueueUpdated(mTracks))
                mCurrTrackCover = getAlbumImage().first
                trackChanged()

                val secs = getPosition()!! / 1000
                broadcastTrackProgress(secs)
            }
            trackStateChanged(isPlaying())
        }
    }

    private fun initMediaPlayerIfNeeded() {
        if (mPlayer != null) {
            return
        }

        mPlayer = MediaPlayer().apply {
            setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            setAudioStreamType(STREAM_MUSIC)
            setOnPreparedListener(this@MusicService)
            setOnCompletionListener(this@MusicService)
            setOnErrorListener(this@MusicService)
        }
        setupEqualizer()
    }

    private fun setupEqualizer() {
        if (mPlayer == null) {
            return
        }

        try {
            val preset = config.equalizerPreset
            mEqualizer = Equalizer(0, mPlayer!!.audioSessionId)
            if (!mEqualizer!!.enabled) {
                mEqualizer!!.enabled = true
            }

            if (preset != EQUALIZER_PRESET_CUSTOM) {
                mEqualizer!!.usePreset(preset.toShort())
            } else {
                val minValue = mEqualizer!!.bandLevelRange[0]
                val bandType = object : TypeToken<HashMap<Short, Int>>() {}.type
                val equalizerBands = Gson().fromJson<HashMap<Short, Int>>(config.equalizerBands, bandType) ?: HashMap()

                for ((key, value) in equalizerBands) {
                    val newValue = value + minValue
                    if (mEqualizer!!.getBandLevel(key) != newValue.toShort()) {
                        mEqualizer!!.setBandLevel(key, newValue.toShort())
                    }
                }
            }
        } catch (ignored: Exception) {
        }
    }

    // make sure tracks don't get duplicated in the queue, if they exist in multiple playlists
    private fun getQueuedTracks(): ArrayList<Track> {
        val tracks = ArrayList<Track>()
        val allTracks = tracksDAO.getAll()
        val wantedIds = queueDAO.getAll().map { it.trackId }

        // make sure we fetch the songs in the order they were displayed in
        val wantedTracks = ArrayList<Track>()
        for (wantedId in wantedIds) {
            val wantedTrack = allTracks.firstOrNull { it.mediaStoreId == wantedId }
            if (wantedTrack != null) {
                wantedTracks.add(wantedTrack)
                continue
            }
        }

        tracks.addAll(wantedTracks)
        return tracks.distinctBy { it.mediaStoreId }.toMutableList() as ArrayList<Track>
    }

    private fun checkTrackOrder() {
        if (config.isShuffleEnabled) {
            mTracks.shuffle()

            if (mCurrTrack != null) {
                mTracks.remove(mCurrTrack)
                mTracks.add(0, mCurrTrack!!)
            }
        }
    }

    private fun startForegroundAndNotify() {
        notificationHandler.removeCallbacksAndMessages(null)
        notificationHandler.postDelayed({
            if (mCurrTrackCover?.isRecycled == true) {
                mCurrTrackCover = resources.getColoredBitmap(R.drawable.ic_headset, getProperTextColor())
            }

            notificationHelper?.createPlayerNotification(
                track = mCurrTrack,
                isPlaying = isPlaying(),
                largeIcon = mCurrTrackCover,
            ) {
                notificationHelper?.notify(NOTIFICATION_ID, it)
                try {
                    if (isQPlus()) {
                        startForeground(NOTIFICATION_ID, it, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    } else {
                        startForeground(NOTIFICATION_ID, it)
                    }
                } catch (ignored: IllegalStateException) {
                }
            }
        }, 200L)
    }

    private fun stopForegroundAndNotification() {
        notificationHandler.removeCallbacksAndMessages(null)
        @Suppress("DEPRECATION")
        stopForeground(true)
        notificationHelper?.cancel(NOTIFICATION_ID)
    }

    private fun getNextQueueItem(): QueueItem {
        return when (mTracks.size) {
            0 -> QueueItem.from(-1L)
            1 -> QueueItem.from(mTracks.first().mediaStoreId)
            else -> {
                val currentTrackIndex = mTracks.indexOfFirstOrNull { it.mediaStoreId == mCurrTrack?.mediaStoreId }
                if (currentTrackIndex != null) {
                    val nextTrack = mTracks[(currentTrackIndex + 1) % mTracks.size]
                    QueueItem.from(nextTrack.mediaStoreId)
                } else {
                    queueDAO.getCurrent() ?: QueueItem.from(mTracks.first().mediaStoreId)
                }
            }
        }
    }

    private fun isEndOfPlaylist(): Boolean {
        return when (mTracks.size) {
            0, 1 -> true
            else -> mCurrTrack?.mediaStoreId == mTracks.last().mediaStoreId
        }
    }

    private fun playPreviousTrack() {
        if (mTracks.isEmpty()) {
            handleEmptyPlaylist()
            return
        }

        initMediaPlayerIfNeeded()

        // play the previous track if we are less than 5 secs into it, else restart
        val currentTrackIndex = mTracks.indexOfFirstOrNull { it.mediaStoreId == mCurrTrack?.mediaStoreId } ?: 0
        if (currentTrackIndex == 0 || getPosition()!! > 5000) {
            restartTrack()
        } else {
            val previousTrack = mTracks[currentTrackIndex - 1]
            setTrack(previousTrack.mediaStoreId)
        }
    }

    fun pauseTrack(notify: Boolean = true) {
        initMediaPlayerIfNeeded()
        mPlayer!!.pause()
        trackStateChanged(false, notify = notify)
        updateMediaSessionState()
        saveTrackProgress()
        // do not call stopForeground on android 12 as it may cause a crash later
        if (!isSPlus()) {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    fun resumeTrack() {
        if (mTracks.isEmpty()) {
            handleEmptyPlaylist()
            return
        }

        initMediaPlayerIfNeeded()

        if (mCurrTrack == null) {
            setupNextTrack()
        } else {
            mPlayer!!.start()
            requestAudioFocus()
        }

        setupEqualizer()
        trackStateChanged(true)
        setPlaybackSpeed()
    }

    private fun setupNextTrack() {
        if (mIsThirdPartyIntent) {
            setupTrack()
        } else {
            ensureBackgroundThread {
                val queueItem = getNextQueueItem()
                mSetProgressOnPrepare = queueItem.lastPosition
                setTrack(queueItem.trackId)
            }
        }
    }

    private fun restartTrack() {
        if (mCurrTrack != null) {
            setTrack(mCurrTrack!!.mediaStoreId)
        }
    }

    private fun playTrack(intent: Intent) {
        if (mIsThirdPartyIntent) {
            setupTrack()
        } else {
            mPlayOnPrepare = true
            val trackId = intent.getLongExtra(TRACK_ID, 0L)
            setTrack(trackId)
            broadcastTrackChange()
        }

        mMediaSession?.isActive = true
    }

    private fun setTrack(wantedTrackId: Long) {
        if (mTracks.isEmpty()) {
            handleEmptyPlaylist()
            return
        }

        initMediaPlayerIfNeeded()
        mPlayer?.reset() ?: return
        mCurrTrack = mTracks.firstOrNull { it.mediaStoreId == wantedTrackId } ?: return

        try {
            val trackUri = if (mCurrTrack!!.mediaStoreId == 0L) {
                Uri.fromFile(File(mCurrTrack!!.path))
            } else {
                ContentUris.withAppendedId(Audio.Media.EXTERNAL_CONTENT_URI, mCurrTrack!!.mediaStoreId)
            }

            mPlayer!!.setDataSource(applicationContext, trackUri)
            mPlayer!!.prepareAsync()
            trackChanged()
        } catch (e: IOException) {
            if (mCurrTrack != null) {
                val trackToDelete = mCurrTrack
                ensureBackgroundThread {
                    tracksDAO.removeTrack(trackToDelete!!.mediaStoreId)
                }
            }

            if (mRetriedTrackCount < 3) {
                mRetriedTrackCount++
                setupNextTrack()
            }
        } catch (ignored: Exception) {
        }
    }

    private fun handleEmptyPlaylist() {
        mPlayer?.pause()
        abandonAudioFocus()
        mCurrTrack = null
        trackChanged()
        trackStateChanged(false)

        if (!mIsServiceInitialized) {
            handleInit()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCompletion(mp: MediaPlayer) {
        if (!config.autoplay) {
            return
        }

        val playbackSetting = config.playbackSetting

        mPlayOnPrepare = when (playbackSetting) {
            PlaybackSetting.REPEAT_OFF -> !isEndOfPlaylist()
            PlaybackSetting.REPEAT_PLAYLIST, PlaybackSetting.REPEAT_TRACK -> true
            PlaybackSetting.STOP_AFTER_CURRENT_TRACK -> false
        }

        when (config.playbackSetting) {
            PlaybackSetting.REPEAT_OFF -> {
                if (isEndOfPlaylist()) {
                    broadcastTrackProgress(0)
                    setupNextTrack()
                } else {
                    setupNextTrack()
                }
            }
            PlaybackSetting.REPEAT_PLAYLIST -> setupNextTrack()
            PlaybackSetting.REPEAT_TRACK -> restartTrack()
            PlaybackSetting.STOP_AFTER_CURRENT_TRACK -> {
                broadcastTrackProgress(0)
                restartTrack()
            }
        }
    }

    override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        mPlayer!!.reset()
        return false
    }

    override fun onPrepared(mp: MediaPlayer) {
        mRetriedTrackCount = 0
        if (mPlayOnPrepare) {
            mp.start()
            requestAudioFocus()

            try {
                mp.playbackParams = mp.playbackParams.setSpeed(config.playbackSpeed)
            } catch (ignored: Exception) {
            }

            if (mIsThirdPartyIntent) {
                trackChanged()
            }
        }
        if (mSetProgressOnPrepare > 0) {
            mp.seekTo(mSetProgressOnPrepare)
            broadcastTrackProgress(mSetProgressOnPrepare / 1000)
            mSetProgressOnPrepare = 0
        }

        trackStateChanged(isPlaying())
    }

    private fun trackChanged() {
        broadcastTrackChange()
        updateMediaSession()
        updateMediaSessionState()
    }

    private fun updateMediaSession() {
        val (albumImage, isPlaceholder) = getAlbumImage()
        mCurrTrackCover = albumImage
        var lockScreenImage = if (isPlaceholder) albumImage else null
        if (lockScreenImage == null || lockScreenImage.isRecycled) {
            try {
                lockScreenImage = resources.getColoredBitmap(R.drawable.ic_headset, getProperTextColor())
            } catch (ignored: OutOfMemoryError) {
            }
        }

        val metadata = MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, lockScreenImage)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, mCurrTrack?.album ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, mCurrTrack?.artist ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, mCurrTrack?.title ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mCurrTrack?.mediaStoreId?.toString())
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (mCurrTrack?.duration?.toLong() ?: 0L) * 1000)
            .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, mTracks.indexOf(mCurrTrack).toLong() + 1)
            .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, mTracks.size.toLong())
            .build()

        mMediaSession?.setMetadata(metadata)
    }

    private fun updateMediaSessionState() {
        val builder = PlaybackStateCompat.Builder()
        val playbackState = if (isPlaying()) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val dismissAction = PlaybackStateCompat.CustomAction.Builder(
            DISMISS,
            getString(R.string.dismiss),
            R.drawable.ic_cross_vector
        ).build()

        builder
            .setActions(mMediaSessionActions)
            .setState(playbackState, getPosition()?.toLong() ?: 0L, mPlaybackSpeed)
            .addCustomAction(dismissAction)
        try {
            mMediaSession?.setPlaybackState(builder.build())
        } catch (ignored: Exception) {
        }
    }

    private fun updateQueueSize() {
        updateMediaSession()
    }

    @SuppressLint("NewApi")
    private fun setPlaybackSpeed() {
        if (mPlayer != null) {
            mPlaybackSpeed = config.playbackSpeed
            if (mPlayer!!.isPlaying) {
                try {
                    mPlayer!!.playbackParams = mPlayer!!.playbackParams.setSpeed(config.playbackSpeed)
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun broadcastTrackChange() {
        Handler(Looper.getMainLooper()).post {
            broadcastUpdateWidgetState()
            EventBus.getDefault().post(Events.TrackChanged(mCurrTrack))
            broadcastNextTrackChange()
        }
        saveTrackProgress()
    }

    private fun broadcastTrackStateChange(isPlaying: Boolean) {
        broadcastUpdateWidgetState()
        EventBus.getDefault().post(Events.TrackStateChanged(isPlaying))
    }

    private fun broadcastNextTrackChange() {
        setPlaybackSpeed()
        Handler(Looper.getMainLooper()).post {
            var currentTrackIndex: Int? = null
            for (i in 0 until mTracks.size) {
                val track = mTracks[i]
                if (track.mediaStoreId == mCurrTrack?.mediaStoreId) {
                    currentTrackIndex = i
                    break
                }
            }

            if (currentTrackIndex != null) {
                val nextTrack = mTracks[(currentTrackIndex + 1) % mTracks.size]
                EventBus.getDefault().post(Events.NextTrackChanged(nextTrack))
            }
        }
    }

    private fun broadcastTrackProgress(progress: Int) {
        EventBus.getDefault().post(Events.ProgressUpdated(progress))
        updateMediaSessionState()
    }

    private fun getPosition(): Int? {
        return mPlayer?.currentPosition
    }

    // do not just return the album cover, but also a boolean to indicate if it a real cover, or just the placeholder
    @SuppressLint("NewApi")
    private fun getAlbumImage(): Pair<Bitmap, Boolean> {
        if (File(mCurrTrack?.path ?: "").exists()) {
            try {
                try {
                    val mediaMetadataRetriever = MediaMetadataRetriever()
                    mediaMetadataRetriever.setDataSource(mCurrTrack!!.path)
                    val rawArt = mediaMetadataRetriever.embeddedPicture
                    if (rawArt != null) {
                        val options = BitmapFactory.Options()
                        val bitmap = BitmapFactory.decodeByteArray(rawArt, 0, rawArt.size, options)
                        if (bitmap != null) {
                            val resultBitmap = if (bitmap.height > mCoverArtHeight * 2) {
                                val ratio = bitmap.width / bitmap.height.toFloat()
                                Bitmap.createScaledBitmap(bitmap, (mCoverArtHeight * ratio).toInt(), mCoverArtHeight, false)
                            } else {
                                bitmap
                            }
                            return Pair(resultBitmap, true)
                        }
                    }
                } catch (ignored: OutOfMemoryError) {
                } catch (ignored: Exception) {
                }

                val trackParentDirectory = File(mCurrTrack!!.path).parent?.trimEnd('/')
                val albumArtFiles = arrayListOf("folder.jpg", "albumart.jpg", "cover.jpg")
                albumArtFiles.forEach {
                    val albumArtFilePath = "$trackParentDirectory/$it"
                    if (File(albumArtFilePath).exists()) {
                        val bitmap = BitmapFactory.decodeFile(albumArtFilePath)
                        if (bitmap != null) {
                            val resultBitmap = if (bitmap.height > mCoverArtHeight * 2) {
                                val ratio = bitmap.width / bitmap.height.toFloat()
                                Bitmap.createScaledBitmap(bitmap, (mCoverArtHeight * ratio).toInt(), mCoverArtHeight, false)
                            } else {
                                bitmap
                            }
                            return Pair(resultBitmap, true)
                        }
                    }
                }
            } catch (ignored: Exception) {
            } catch (ignored: Error) {
            }
        }

        if (isQPlus()) {
            if (mCurrTrack?.coverArt?.startsWith("content://") == true) {
                try {
                    val thumbnail = MediaStore.Images.Media.getBitmap(contentResolver, Uri.parse(mCurrTrack!!.coverArt))
                    return Pair(thumbnail, true)
                } catch (ignored: Exception) {
                }
            }

            if (mCurrTrack?.path?.startsWith("content://") == true) {
                try {
                    val size = Size(512, 512)
                    val thumbnail = contentResolver.loadThumbnail(Uri.parse(mCurrTrack!!.path), size, null)
                    return Pair(thumbnail, true)
                } catch (ignored: Exception) {
                }
            }
        }

        if (mHeadsetPlaceholder == null) {
            mHeadsetPlaceholder = resources.getColoredBitmap(R.drawable.ic_headset, getProperTextColor())
        }

        return Pair(mHeadsetPlaceholder!!, false)
    }

    private fun destroyPlayer() {
        if (!mIsThirdPartyIntent) {
            ensureBackgroundThread {
                try {
                    saveTrackProgress()
                    mTracks.forEachIndexed { index, track ->
                        queueDAO.setOrder(track.mediaStoreId, index)
                    }
                } catch (ignored: Exception) {
                }

                mCurrTrack = null
            }
        } else {
            mCurrTrack = null
        }

        mPlayer?.stop()
        mPlayer?.release()
        mPlayer = null

        trackStateChanged(isPlaying = false, notify = false)
        trackChanged()

        stopForegroundAndNotification()
        stopSelf()
        mIsThirdPartyIntent = false
        mIsServiceInitialized = false
        abandonAudioFocus()
    }

    private fun requestAudioFocus() {
        if (isOreoPlus()) {
            mOreoFocusHandler?.requestAudioFocus(this)
        } else {
            mAudioManager?.requestAudioFocus(this, STREAM_MUSIC, AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        if (isOreoPlus()) {
            mOreoFocusHandler?.abandonAudioFocus()
        } else {
            mAudioManager?.abandonAudioFocus(this)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AUDIOFOCUS_GAIN -> audioFocusGained()
            AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duckAudio()
            AUDIOFOCUS_LOSS, AUDIOFOCUS_LOSS_TRANSIENT -> audioFocusLost()
        }
        mPrevAudioFocusState = focusChange
    }

    private fun audioFocusLost() {
        if (isPlaying()) {
            mWasPlayingAtFocusLost = true
            pauseTrack()
        } else {
            mWasPlayingAtFocusLost = false
        }
    }

    private fun audioFocusGained() {
        if (mWasPlayingAtFocusLost) {
            if (mPrevAudioFocusState == AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                unduckAudio()
            } else {
                resumeTrack()
            }
        }

        mWasPlayingAtFocusLost = false
    }

    private fun duckAudio() {
        mPlayer?.setVolume(0.3f, 0.3f)
        mWasPlayingAtFocusLost = isPlaying()
    }

    private fun unduckAudio() {
        mPlayer?.setVolume(1f, 1f)
    }

    fun updateProgress(progress: Int) {
        mPlayer!!.seekTo(progress * 1000)
        saveTrackProgress()
        resumeTrack()
    }

    private fun trackStateChanged(isPlaying: Boolean, notify: Boolean = true) {
        handleProgressHandler(isPlaying)
        broadcastTrackStateChange(isPlaying)
        if (notify) {
            startForegroundAndNotify()
        }

        if (isPlaying) {
            val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
            filter.addAction(ACTION_AUDIO_BECOMING_NOISY)
            registerReceiver(mHeadsetPlugReceiver, filter)
        } else {
            try {
                unregisterReceiver(mHeadsetPlugReceiver)
            } catch (ignored: IllegalArgumentException) {
            }
        }
    }

    private fun handleProgressHandler(isPlaying: Boolean) {
        if (isPlaying) {
            mProgressHandler.post(object : Runnable {
                override fun run() {
                    if (mPlayer?.isPlaying == true) {
                        val secs = getPosition()!! / 1000
                        broadcastTrackProgress(secs)
                    }
                    mProgressHandler.removeCallbacksAndMessages(null)
                    mProgressHandler.postDelayed(this, (PROGRESS_UPDATE_INTERVAL / mPlaybackSpeed).toLong())
                }
            })
        } else {
            mProgressHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun skip(forward: Boolean) {
        val curr = getPosition() ?: return
        val newProgress = if (forward) curr + FAST_FORWARD_SKIP_MS else curr - FAST_FORWARD_SKIP_MS
        mPlayer!!.seekTo(newProgress)
        resumeTrack()
    }

    private fun startSleepTimer() {
        val millisInFuture = config.sleepInTS - System.currentTimeMillis() + 1000L
        mSleepTimer?.cancel()
        mSleepTimer = object : CountDownTimer(millisInFuture, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                EventBus.getDefault().post(Events.SleepTimerChanged(seconds))
            }

            override fun onFinish() {
                EventBus.getDefault().post(Events.SleepTimerChanged(0))
                config.sleepInTS = 0
                sendIntent(DISMISS)
            }
        }
        mSleepTimer?.start()
    }

    private fun stopSleepTimer() {
        config.sleepInTS = 0
        mSleepTimer?.cancel()
    }

    // used at updating the widget at create or resize
    private fun broadcastPlayerStatus() {
        broadcastTrackStateChange(mPlayer?.isPlaying ?: false)
        broadcastTrackChange()
        broadcastNextTrackChange()
        broadcastTrackProgress((getPosition() ?: 0) / 1000)
    }

    fun handleMediaButton(mediaButtonEvent: Intent) {
        if (mediaButtonEvent.action == Intent.ACTION_MEDIA_BUTTON) {
            val swapPrevNext = config.swapPrevNext
            val event = mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
            if (event.action == KeyEvent.ACTION_UP) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> resumeTrack()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> pauseTrack()
                    KeyEvent.KEYCODE_MEDIA_STOP -> pauseTrack()
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> handlePlayPause()
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (swapPrevNext) handleNext() else handlePrevious()
                    KeyEvent.KEYCODE_MEDIA_NEXT -> if (swapPrevNext) handlePrevious() else handleNext()
                    KeyEvent.KEYCODE_HEADSETHOOK -> {
                        mClicksCnt++

                        mRemoteControlHandler.removeCallbacks(mRunnable)
                        if (mClicksCnt >= 3) {
                            mRemoteControlHandler.post(mRunnable)
                        } else {
                            mRemoteControlHandler.postDelayed(mRunnable, MAX_CLICK_DURATION)
                        }
                    }
                }
            }
        }
    }

    private fun saveTrackProgress() {
        if (mCurrTrack != null) {
            ensureBackgroundThread {
                val trackId = mCurrTrack?.mediaStoreId ?: return@ensureBackgroundThread
                val position = getPosition()
                queueDAO.apply {
                    resetCurrent()
                    if (position == null || position == 0) {
                        saveCurrentTrack(trackId)
                    } else {
                        saveCurrentTrackProgress(trackId, position)
                    }
                }
            }
        }
    }
}
