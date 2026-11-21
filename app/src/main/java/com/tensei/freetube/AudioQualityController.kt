package com.tensei.freetube

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

internal enum class AudioQuality(val label: String) {
    AUTO("Auto"), HIGH("High"), DATA_SAVER("Data Saver");

    fun rank(average: Int, bitrate: Int): Int {
        if (this == AUTO) return average
        val rate = if (average > 0) average else bitrate
        return when {
            rate <= 0 -> Int.MIN_VALUE
            this == HIGH -> rate
            else -> -rate
        }
    }
}

internal class AudioQualityController(private val player: Player) : Player.Listener {
    private var owned: TrackSelectionOverride? = null
    private var mode = AudioQuality.AUTO

    fun apply(value: AudioQuality) {
        mode = value
        update(player.currentTracks)
    }

    override fun onTracksChanged(tracks: Tracks) = update(tracks)

    private fun update(tracks: Tracks) {
        val current = player.trackSelectionParameters
        val builder = current.buildUpon().setMaxAudioBitrate(if (mode == AudioQuality.DATA_SAVER) 96000 else Int.MAX_VALUE)
        val previous = owned
        if (previous != null && current.overrides[previous.mediaTrackGroup] == previous) {
            builder.clearOverride(previous.mediaTrackGroup)
        }
        owned = null
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        // Multiple groups may represent languages or descriptive audio. Leave that choice with Media3.
        val group = audio.singleOrNull()
        if (mode == AudioQuality.HIGH && group != null && group.length > 1 &&
            current.overrides.values.none { it.type == C.TRACK_TYPE_AUDIO && it != previous }) {
            val candidates = (0 until group.length).filter { group.isTrackSupported(it) }
            val roles = candidates.map { group.getTrackFormat(it).let { f -> Triple(f.language, f.roleFlags, f.label) } }
            val best = candidates.filter { group.getTrackFormat(it).bitrate > 0 }.maxWithOrNull(
                compareBy<Int> { group.getTrackFormat(it).bitrate }.thenBy { group.getTrackFormat(it).channelCount })
            if (roles.distinct().size == 1 && best != null) {
                val override = TrackSelectionOverride(group.mediaTrackGroup, best)
                builder.addOverride(override)
                owned = override
            }
        }
        val next = builder.build()
        if (next != current) player.trackSelectionParameters = next
    }
}
