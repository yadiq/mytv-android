package top.yogiczy.mytv.tv.ui.screensold.videoplayer.player

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.util.utils.toHeaders
import top.yogiczy.mytv.tv.ui.utils.Configs

@OptIn(UnstableApi::class)
class Media3VideoPlayer(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
) : VideoPlayer(coroutineScope) {

    private var videoPlayer = getPlayer()

    private var softDecode: Boolean? = null
    private var surfaceView: SurfaceView? = null
    private var textureView: TextureView? = null

    private var currentChannelLine = ChannelLine()
    private val contentTypeAttempts = mutableMapOf<Int, Boolean>()
    private var updatePositionJob: Job? = null

    private val onCuesListeners = mutableListOf<(ImmutableList<Cue>) -> Unit>()

    private fun triggerCues(cues: ImmutableList<Cue>) {
        onCuesListeners.forEach { it(cues) }
    }

    fun onCues(listener: (ImmutableList<Cue>) -> Unit) {
        onCuesListeners.add(listener)
    }

    private fun getPlayer(): ExoPlayer {
        val renderersFactory =
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(
                    if (softDecode ?: Configs.videoPlayerForceAudioSoftDecode)
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                )
                .setEnableDecoderFallback(true)

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE)
                .setForceHighestSupportedBitrate(true)
                .setPreferredTextLanguages("zh")
                .build()
        }

//        MediaCodecVideoRenderer.skipMultipleFramesOnSameVsync =
//            Configs.videoPlayerSkipMultipleFramesOnSameVSync
        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .build()
            .apply { playWhenReady = true }
    }

    private fun reInitPlayer() {
        onCuesListeners.clear()
        videoPlayer.removeListener(playerListener)
        videoPlayer.removeAnalyticsListener(metadataListener)
        videoPlayer.removeAnalyticsListener(eventLogger)
        videoPlayer.stop()
        videoPlayer.release()

        videoPlayer = getPlayer()

        videoPlayer.addListener(playerListener)
        videoPlayer.addAnalyticsListener(metadataListener)
        videoPlayer.addAnalyticsListener(eventLogger)

        surfaceView?.let { setVideoSurfaceView(it) }
        textureView?.let { setVideoTextureView(it) }
        prepare()
    }

    private fun getDataSourceFactory(): DefaultDataSource.Factory {
        return DefaultDataSource.Factory(
            context,
            DefaultHttpDataSource.Factory().apply {
                setUserAgent(currentChannelLine.httpUserAgent ?: Configs.videoPlayerUserAgent)
                setDefaultRequestProperties(Configs.videoPlayerHeaders.toHeaders())
                setConnectTimeoutMs(Configs.videoPlayerLoadTimeout.toInt())
                setReadTimeoutMs(Configs.videoPlayerLoadTimeout.toInt())
                setKeepPostFor302Redirects(true)
                setAllowCrossProtocolRedirects(true)
            },
        )
    }

    private fun getMediaSource(contentType: Int? = null): MediaSource? {
        val uri = Uri.parse(currentChannelLine.playableUrl)
        val mediaItem = MediaItem.fromUri(uri)

        var contentTypeForce = contentType

        if (uri.toString().startsWith("rtp://")) {
            contentTypeForce = C.CONTENT_TYPE_RTSP
        }

        if (currentChannelLine.manifestType == "mpd") {
            contentTypeForce = C.CONTENT_TYPE_DASH
        }

        val dataSourceFactory = getDataSourceFactory()
        return when (contentTypeForce ?: Util.inferContentType(uri)) {
            C.CONTENT_TYPE_HLS -> {
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }

            C.CONTENT_TYPE_DASH -> {
                DashMediaSource.Factory(dataSourceFactory)
                    .apply {
                        if (!(currentChannelLine.manifestType == "mpd" && currentChannelLine.licenseType == "clearkey" && currentChannelLine.licenseKey != null))
                            return@apply

                        runCatching {
                            val (drmKeyId, drmKey) = currentChannelLine.licenseKey!!.split(":")
                            val encodedDrmKey = Base64.encodeToString(
                                drmKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                            )
                            val encodedDrmKeyId = Base64.encodeToString(
                                drmKeyId.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                            )
                            val drmBody =
                                "{\"keys\":[{\"kty\":\"oct\",\"k\":\"${encodedDrmKey}\",\"kid\":\"${encodedDrmKeyId}\"}],\"type\":\"temporary\"}"

                            val drmCallback = LocalMediaDrmCallback(drmBody.toByteArray())
                            val drmSessionManager = DefaultDrmSessionManager.Builder()
                                .setMultiSession(true)
                                .setUuidAndExoMediaDrmProvider(
                                    C.CLEARKEY_UUID,
                                    FrameworkMediaDrm.DEFAULT_PROVIDER
                                )
                                .build(drmCallback)

                            setDrmSessionManagerProvider { drmSessionManager }
                        }
                            .onFailure {
                                triggerError(
                                    PlaybackException(
                                        "MEDIA3_ERROR_DRM_LICENSE_EXPIRED",
                                        androidx.media3.common.PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED
                                    )
                                )
                            }
                    }
                    .createMediaSource(mediaItem)
            }

            C.CONTENT_TYPE_RTSP -> {
                RtspMediaSource.Factory().createMediaSource(mediaItem)
            }

            C.CONTENT_TYPE_OTHER -> {
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }

            else -> {
                triggerError(PlaybackException.UNSUPPORTED_TYPE)
                null
            }
        }
    }

    private fun prepare(contentType: Int? = null) {
        val uri = Uri.parse(currentChannelLine.playableUrl)
        val mediaSource = getMediaSource(contentType)

        if (mediaSource != null) {
            contentTypeAttempts[contentType ?: Util.inferContentType(uri)] = true
            videoPlayer.setMediaSource(mediaSource)
            videoPlayer.prepare()
            videoPlayer.play()
            triggerPrepared()
        }
        updatePositionJob?.cancel()
        updatePositionJob = null
    }

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            triggerResolution(videoSize.width, videoSize.height)
        }

        override fun onPlayerError(ex: androidx.media3.common.PlaybackException) {
            when (ex.errorCode) {
                // 如果是直播加载位置错误，尝试重新播放
                androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                    videoPlayer.seekToDefaultPosition()
                    videoPlayer.prepare()
                }

                // 当解析容器不支持时，尝试使用其他解析容器
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> {
                    videoPlayer.currentMediaItem?.localConfiguration?.uri?.let {
                        if (contentTypeAttempts[C.CONTENT_TYPE_HLS] != true) {
                            prepare(C.CONTENT_TYPE_HLS)
                        } else if (contentTypeAttempts[C.CONTENT_TYPE_DASH] != true) {
                            prepare(C.CONTENT_TYPE_DASH)
                        } else if (contentTypeAttempts[C.CONTENT_TYPE_RTSP] != true) {
                            prepare(C.CONTENT_TYPE_RTSP)
                        } else if (contentTypeAttempts[C.CONTENT_TYPE_OTHER] != true) {
                            prepare(C.CONTENT_TYPE_OTHER)
                        } else {
                            triggerError(PlaybackException.UNSUPPORTED_TYPE)
                        }
                    }
                }

                androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> {
                    if (softDecode == true) {
                        triggerError(
                            PlaybackException(
                                ex.errorCodeName.replace("ERROR_CODE", "MEDIA3_ERROR"),
                                ex.errorCode
                            )
                        )
                    } else {
                        softDecode = true
                        reInitPlayer()
                    }
                }

                else -> {
                    triggerError(
                        PlaybackException(
                            ex.errorCodeName.replace("ERROR_CODE", "MEDIA3_ERROR"),
                            ex.errorCode
                        )
                    )
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING) {
                triggerError(null)
                triggerBuffering(true)
            } else if (playbackState == Player.STATE_READY) {
                triggerReady()
                triggerDuration(videoPlayer.duration)

                updatePositionJob?.cancel()
                updatePositionJob = coroutineScope.launch {
                    while (true) {
                        val livePosition =
                            System.currentTimeMillis() - videoPlayer.currentLiveOffset

                        triggerCurrentPosition(if (livePosition > 0) livePosition else videoPlayer.currentPosition)
                        delay(500)
                    }
                }
            }

            if (playbackState != Player.STATE_BUFFERING) {
                triggerBuffering(false)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            triggerIsPlayingChanged(isPlaying)
        }

        override fun onTracksChanged(tracks: Tracks) {
            metadata = metadata.copy(
                videoTracks = emptyList(),
                audioTracks = emptyList(),
                subtitleTracks = emptyList()
            )
            triggerMetadata(metadata)

            val videoFormats = videoPlayer.currentTracks.groups
                .filter { it.mediaTrackGroup.type == C.TRACK_TYPE_VIDEO }
                .flatMap { group ->
                    List(group.mediaTrackGroup.length) { trackIndex ->
                        group.mediaTrackGroup
                            .getFormat(trackIndex)
                            .toVideoMetadata()
                            .copy(isSelected = group.isTrackSelected(trackIndex))
                    }
                }
                .mapIndexed { index, metadata ->
                    metadata.copy(index = index)
                }

            metadata = metadata.copy(videoTracks = videoFormats)

            val audioFormats = videoPlayer.currentTracks.groups
                .filter { it.mediaTrackGroup.type == C.TRACK_TYPE_AUDIO }
                .flatMap { group ->
                    List(group.mediaTrackGroup.length) { trackIndex ->
                        group.mediaTrackGroup
                            .getFormat(trackIndex)
                            .toAudioMetadata()
                            .copy(isSelected = group.isTrackSelected(trackIndex))
                    }
                }
                .mapIndexed { index, metadata ->
                    metadata.copy(index = index)
                }

            metadata = metadata.copy(audioTracks = audioFormats)

            val subtitleFormats = videoPlayer.currentTracks.groups
                .filter { it.mediaTrackGroup.type == C.TRACK_TYPE_TEXT }
                .flatMap { group ->
                    List(group.mediaTrackGroup.length) { trackIndex ->
                        group.mediaTrackGroup
                            .getFormat(trackIndex)
                            .takeIf { it.roleFlags == C.ROLE_FLAG_SUBTITLE }
                            ?.toSubtitleMetadata()
                            ?.copy(isSelected = group.isTrackSelected(trackIndex))
                    }
                }
                .mapNotNull { it }
                .mapIndexed { index, metadata ->
                    metadata.copy(index = index)
                }

            metadata = metadata.copy(subtitleTracks = subtitleFormats)

            triggerMetadata(metadata)
        }

        override fun onCues(cueGroup: CueGroup) {
            triggerCues(cueGroup.cues)
        }
    }

    private fun Int.fromIndexFindTrack(type: @C.TrackType Int): Pair<TrackGroup, Int> {
        val groups = videoPlayer.currentTracks.groups
            .filter { group -> group.mediaTrackGroup.type == type }
            .map { it.mediaTrackGroup }

        var trackCount = 0
        val group = groups.first { group ->
            trackCount += group.length
            this < trackCount
        }

        val trackIndex = this - (trackCount - group.length)

        return Pair(group, trackIndex)
    }

    private fun Format.toVideoMetadata(video: Metadata.Video? = null): Metadata.Video {
        return (video ?: Metadata.Video()).copy(
            width = width,
            height = height,
            color = colorInfo?.toLogString(),
            frameRate = frameRate,
            // TODO 帧率、比特率目前是从tag中获取，有的返回空，后续需要实时计算
            bitrate = bitrate,
            mimeType = sampleMimeType,
        )
    }

    private fun Format.toAudioMetadata(audio: Metadata.Audio? = null): Metadata.Audio {
        return (audio ?: Metadata.Audio()).copy(
            channels = channelCount,
//            channelsLabel = if (sampleMimeType == MimeTypes.AUDIO_AV3A) "菁彩声" else null,
            sampleRate = sampleRate,
            bitrate = bitrate,
            mimeType = sampleMimeType,
            language = language,
        )
    }

    private fun Format.toSubtitleMetadata(subtitle: Metadata.Subtitle? = null): Metadata.Subtitle {
        return (subtitle ?: Metadata.Subtitle()).copy(
            bitrate = bitrate,
            mimeType = sampleMimeType,
            language = language,
        )
    }

    private val metadataListener = object : AnalyticsListener {
        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            metadata = metadata.copy(video = format.toVideoMetadata(metadata.video))
            triggerMetadata(metadata)
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            metadata = metadata.copy(
                video = (metadata.video ?: Metadata.Video()).copy(decoder = decoderName)
            )

            triggerMetadata(metadata)
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            metadata = metadata.copy(audio = format.toAudioMetadata(metadata.audio))
            triggerMetadata(metadata)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            metadata = metadata.copy(
                audio = (metadata.audio ?: Metadata.Audio()).copy(decoder = decoderName)
            )

            triggerMetadata(metadata)
        }
    }

    private val eventLogger = EventLogger()

    override fun initialize() {
        super.initialize()
        videoPlayer.addListener(playerListener)
        videoPlayer.addAnalyticsListener(metadataListener)
        videoPlayer.addAnalyticsListener(eventLogger)
    }

    override fun release() {
        onCuesListeners.clear()
        videoPlayer.removeListener(playerListener)
        videoPlayer.removeAnalyticsListener(metadataListener)
        videoPlayer.removeAnalyticsListener(eventLogger)
        videoPlayer.stop()
        videoPlayer.release()
        super.release()
    }

    override fun prepare(line: ChannelLine) {
        if (Configs.videoPlayerStopPreviousMediaItem)
            videoPlayer.stop()

        contentTypeAttempts.clear()
        currentChannelLine = line
        prepare(null)
    }

    override fun play() {
        videoPlayer.play()
    }

    override fun pause() {
        videoPlayer.pause()
    }

    override fun seekTo(position: Long) {
        videoPlayer.seekTo(position)
    }

    override fun setVolume(volume: Float) {
        videoPlayer.volume = volume
    }

    override fun getVolume(): Float {
        return videoPlayer.volume
    }

    override fun stop() {
        videoPlayer.stop()
        updatePositionJob?.cancel()
        super.stop()
    }

    override fun selectVideoTrack(track: Metadata.Video?) {
        if (track?.index == null) {
            videoPlayer.trackSelectionParameters = videoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()

            return
        }

        val (group, trackIndex) = track.index.fromIndexFindTrack(C.TRACK_TYPE_VIDEO)

        videoPlayer.trackSelectionParameters = videoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .setOverrideForType(TrackSelectionOverride(group, trackIndex))
            .build()
    }

    override fun selectAudioTrack(track: Metadata.Audio?) {
        if (track?.index == null) {
            videoPlayer.trackSelectionParameters = videoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()

            return
        }

        val (group, trackIndex) = track.index.fromIndexFindTrack(C.TRACK_TYPE_AUDIO)

        videoPlayer.trackSelectionParameters = videoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group, trackIndex))
            .build()
    }

    override fun selectSubtitleTrack(track: Metadata.Subtitle?) {
        if (track?.language == null) {
            videoPlayer.trackSelectionParameters = videoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()

            return
        }

        videoPlayer.trackSelectionParameters = videoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguages(track.language)
            .build()
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView) {
        this.surfaceView = surfaceView
        videoPlayer.setVideoSurfaceView(surfaceView)
    }

    override fun setVideoTextureView(textureView: TextureView) {
        this.textureView = textureView
        videoPlayer.setVideoTextureView(textureView)
    }
}