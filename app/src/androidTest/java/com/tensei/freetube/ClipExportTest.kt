package com.tensei.freetube

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ClipExportTest {
    @Test fun writesRequestedIntervalForMuxedAdaptiveAndAudioOnly() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File.createTempFile("clip-source", ".mp4", context.cacheDir)
        val uri = Uri.fromFile(input).toString()
        try {
            instrumentation.context.assets.open("clip-source.mp4").use { src -> input.outputStream().use { src.copyTo(it) } }
            for ((video, audio) in listOf(uri to null, uri to uri, null to uri)) {
                val output = File.createTempFile("cut", ".mp4", context.cacheDir)
                val extractor = MediaExtractor()
                try {
                    withTimeout(60000) { ClipExporter.export(context, DownloadClip(1, 3), video, audio, output) }
                    extractor.setDataSource(output.path)
                    val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                    assertEquals(if (video == null) 1 else 2, tracks.size)
                    assertTrue(tracks.any { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") })
                    assertEquals(video != null, tracks.any { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") })
                    for (track in tracks) assertEquals("clip duration in microseconds", 2000000.0,
                        track.getLong(MediaFormat.KEY_DURATION).toDouble(), 150000.0)
                } finally { extractor.release(); output.delete() }
            }
        } finally { input.delete() }
    }
}
