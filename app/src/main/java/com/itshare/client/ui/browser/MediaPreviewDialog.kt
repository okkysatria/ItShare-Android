package com.itshare.client.ui.browser

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.graphics.SurfaceTexture
import android.view.Surface as AndroidSurface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.itshare.client.core.model.SmbFileItem
import com.itshare.client.ui.uiText
import kotlinx.coroutines.delay

@Composable
internal fun MediaPreviewDialog(
    item: SmbFileItem,
    language: String,
    onDismiss: () -> Unit,
    onLoadImage: suspend (SmbFileItem) -> Bitmap?,
    onCreateMediaSource: suspend (SmbFileItem) -> MediaDataSource,
    onDownloadClicked: (() -> Unit)? = null
) {
    BackHandler(onBack = onDismiss)
    var bitmap by remember(item.path) { mutableStateOf<Bitmap?>(null) }
    var source by remember(item.path) { mutableStateOf<MediaDataSource?>(null) }
    var loading by remember(item.path) { mutableStateOf(true) }
    var error by remember(item.path) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.path) {
        loading = true
        error = null
        runCatching {
            if (item.isImageFile()) {
                bitmap = onLoadImage(item)
            } else {
                source = onCreateMediaSource(item)
            }
        }.onFailure {
            error = it.localizedMessage ?: "Unable to open media"
        }
        if (item.isImageFile() && bitmap == null && error == null) {
            error = uiText(language, "Image could not be decoded.", "Gambar tidak dapat dibuka.")
        }
        loading = false
    }

    DisposableEffect(item.path) {
        onDispose {
            runCatching { source?.close() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Column(Modifier.fillMaxSize()) {

                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = uiText(language, "Back", "Kembali")
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    ) {
                        Text(
                            item.name,
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (item.isImageFile()) {
                                uiText(language, "IMAGE", "GAMBAR")
                            } else {
                                uiText(language, "VIDEO", "VIDEO")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (onDownloadClicked != null) {
                        IconButton(onClick = onDownloadClicked) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = uiText(language, "Download", "Unduh"),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        loading -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    uiText(language, "Opening media…", "Membuka media…"),
                                    color = Color.White
                                )
                            }
                        }
                        error != null -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    error!!,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (onDownloadClicked != null) {
                                    Spacer(Modifier.height(16.dp))
                                    Button(onClick = onDownloadClicked) {
                                        Icon(Icons.Default.Download, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(uiText(language, "Download file instead", "Unduh berkas saja"))
                                    }
                                }
                            }
                        }
                        bitmap != null -> {
                            ZoomableImage(bitmap!!)
                        }
                        source != null -> {
                            SmbVideoPlayer(
                                source = source!!,
                                language = language,
                                onDownloadFallback = onDownloadClicked
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offsetX by remember(bitmap) { mutableFloatStateOf(0f) }
    var offsetY by remember(bitmap) { mutableFloatStateOf(0f) }

    val transformState = rememberTransformableState { _, zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offsetX += pan.x
        offsetY += pan.y
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(bitmap) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.2f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .transformable(transformState),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                ),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )
    }
}

@Composable
private fun SmbVideoPlayer(
    source: MediaDataSource,
    language: String,
    onDownloadFallback: (() -> Unit)? = null
) {
    var player by remember(source) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(source) { mutableStateOf(false) }
    var playing by remember(source) { mutableStateOf(false) }
    var duration by remember(source) { mutableIntStateOf(0) }
    var position by remember(source) { mutableIntStateOf(0) }
    var failure by remember(source) { mutableStateOf<String?>(null) }
    var videoSurface by remember(source) { mutableStateOf<AndroidSurface?>(null) }
    var videoWidth by remember(source) { mutableIntStateOf(0) }
    var videoHeight by remember(source) { mutableIntStateOf(0) }

    fun safeAttachSurface(p: MediaPlayer?, s: AndroidSurface?) {
        if (p != null && s != null && s.isValid) {
            runCatching { p.setSurface(s) }
        }
    }

    DisposableEffect(source) {
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            mediaPlayer.setDataSource(source)
            mediaPlayer.setOnPreparedListener { mp ->
                prepared = true
                duration = mp.duration.coerceAtLeast(0)
                videoWidth = mp.videoWidth
                videoHeight = mp.videoHeight
                safeAttachSurface(mp, videoSurface)
                mp.start()
                playing = true
            }
            mediaPlayer.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) {
                    videoWidth = w
                    videoHeight = h
                }
            }
            mediaPlayer.setOnCompletionListener {
                playing = false
                position = duration
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                failure = "${uiText(language, "Playback failed", "Gagal memutar video")} (Code $what/$extra)"
                playing = false
                true
            }
            mediaPlayer.prepareAsync()
        } catch (error: Exception) {
            failure = error.localizedMessage ?: uiText(language, "Playback failed", "Gagal memutar video")
        }

        onDispose {
            runCatching {
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
                mediaPlayer.release()
            }
            player = null
            runCatching { videoSurface?.release() }
            videoSurface = null
            runCatching { source.close() }
        }
    }

    LaunchedEffect(prepared, playing) {
        while (prepared && playing) {
            player?.let {
                position = it.currentPosition.coerceAtLeast(0)
            }
            delay(350)
        }
    }

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .clickable {
                    player?.let {
                        if (playing) {
                            it.pause()
                            playing = false
                        } else if (prepared) {
                            if (position >= duration - 300) it.seekTo(0)
                            it.start()
                            playing = true
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val aspectModifier = if (videoWidth > 0 && videoHeight > 0) {
                Modifier
                    .fillMaxSize()
                    .aspectRatio(videoWidth.toFloat() / videoHeight.toFloat(), matchHeightConstraintsFirst = false)
            } else {
                Modifier.fillMaxSize()
            }

            AndroidView(
                factory = { context: Context ->
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                AndroidSurface(texture).also { surface ->
                                    videoSurface = surface
                                    safeAttachSurface(player, surface)
                                }
                            }
                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                runCatching { videoSurface?.release() }
                                videoSurface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = aspectModifier
            )

            if (!prepared && failure == null) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            if (prepared && !playing && failure == null) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }

            if (failure != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                        .padding(24.dp)
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        failure!!,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (onDownloadFallback != null) {
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = onDownloadFallback) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(uiText(language, "Download to watch", "Unduh untuk menonton"))
                        }
                    }
                }
            }
        }

        Surface(
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Slider(
                    value = position.coerceIn(0, duration.coerceAtLeast(1)).toFloat(),
                    onValueChange = { position = it.toInt() },
                    onValueChangeFinished = {
                        if (prepared) player?.seekTo(position)
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    enabled = prepared
                )

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${formatTime(position)} / ${formatTime(duration)}",
                        style = MaterialTheme.typography.labelMedium
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {

                        IconButton(
                            onClick = {
                                if (prepared) {
                                    val newPos = (position - 10000).coerceAtLeast(0)
                                    player?.seekTo(newPos)
                                    position = newPos
                                }
                            },
                            enabled = prepared
                        ) {
                            Icon(
                                Icons.Default.Replay10,
                                contentDescription = uiText(language, "Rewind 10s", "Mundur 10d")
                            )
                        }

                        FilledIconButton(
                            onClick = {
                                player?.let {
                                    if (playing) {
                                        it.pause()
                                        playing = false
                                    } else if (prepared) {
                                        if (position >= duration - 300) it.seekTo(0)
                                        it.start()
                                        playing = true
                                    }
                                }
                            },
                            enabled = prepared,
                            shape = CircleShape
                        ) {
                            Icon(
                                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = uiText(language, if (playing) "Pause" else "Play", if (playing) "Jeda" else "Putar")
                            )
                        }

                        IconButton(
                            onClick = {
                                if (prepared) {
                                    val newPos = (position + 10000).coerceAtMost(duration)
                                    player?.seekTo(newPos)
                                    position = newPos
                                }
                            },
                            enabled = prepared
                        ) {
                            Icon(
                                Icons.Default.Forward10,
                                contentDescription = uiText(language, "Forward 10s", "Maju 10d")
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0) / 1000)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
