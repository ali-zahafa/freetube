package com.tensei.freetube

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ClipExporter {
    // A single video URI is already muxed. Adaptive video and audio get the same source interval.
    suspend fun export(context: Context, clip: DownloadClip, video: String?, audio: String?, output: File) =
        withContext(Dispatchers.Main.immediate) {
            fun item(uri: String): List<EditedMediaItem> {
                val bounds = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.start * 1000).setEndPositionMs(clip.end * 1000).build()
                val media = MediaItem.Builder().setUri(uri).setClippingConfiguration(bounds).build()
                return listOf(EditedMediaItem.Builder(media).build())
            }
            val sequences = buildList {
                if (video != null) add(if (audio == null) EditedMediaItemSequence.withAudioAndVideoFrom(item(video))
                    else EditedMediaItemSequence.withVideoFrom(item(video)))
                if (audio != null) add(EditedMediaItemSequence.withAudioFrom(item(audio)))
            }
            val done = CompletableDeferred<Unit>()
            val factory = DefaultAssetLoaderFactory(context, DefaultDecoderFactory.Builder(context).build(),
                Clock.DEFAULT, DefaultMediaSourceFactory(YouTubeStreamHttp.createDataSourceFactory(context)),
                DataSourceBitmapLoader.Builder(context).build())
            val transformer = Transformer.Builder(context).setAssetLoaderFactory(factory)
                // Explicit end timestamp keeps the last frame on screen until the selected end.
                .setMuxerFactory(InAppMp4Muxer.Factory().setVideoDurationUs((clip.end - clip.start) * 1_000_000))
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        done.completeExceptionally(exportException)
                    }
                }).build()
            try {
                transformer.start(Composition.Builder(sequences).build(), output.absolutePath)
                done.await()
            } finally { if (!done.isCompleted) transformer.cancel() }
        }
}
