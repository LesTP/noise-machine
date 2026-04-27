package com.movingfingerstudios.noisemachine.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Production [AudioSink] backed by Android `AudioTrack` in streaming PCM mode.
 *
 * Configuration is fixed for Phase 1 (see DECISIONS.md):
 * - 16-bit PCM (`ENCODING_PCM_16_BIT`)
 * - Stereo (`CHANNEL_OUT_STEREO`)
 * - Sample rate provided by [AudioEngine] (D-7: 44100 Hz)
 * - Buffer size = `2 × AudioTrack.getMinBufferSize()` (D-8)
 *
 * The `AudioTrack` instance is single-use: created in [open], destroyed in
 * [close]. The engine creates a fresh `AudioTrackSink` per playback session.
 */
class AudioTrackSink : AudioSink {

    private var track: AudioTrack? = null

    /** Stereo frames per render-quantum write. Independent of the AudioTrack
     *  buffer size, which is sized larger as a glitch margin. */
    private val framesPerWrite = 1024

    override fun open(sampleRateHz: Int, channels: Int): Int {
        require(channels == 2) { "AudioTrackSink Phase-1 supports stereo only (channels=$channels)" }

        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBufferBytes = AudioTrack.getMinBufferSize(sampleRateHz, channelMask, encoding)
        check(minBufferBytes > 0) { "AudioTrack.getMinBufferSize returned $minBufferBytes" }

        // D-8: 2× minimum as a glitch margin for continuous generation.
        // Also enforce that the AudioTrack buffer is at least one render quantum.
        val bytesPerFrame = channels * 2 // 2 bytes per Int16 sample
        val desiredBytes = maxOf(minBufferBytes * 2, framesPerWrite * bytesPerFrame)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRateHz)
            .setChannelMask(channelMask)
            .setEncoding(encoding)
            .build()

        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(desiredBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            .build()

        check(t.state == AudioTrack.STATE_INITIALIZED) {
            "AudioTrack failed to initialize (state=${t.state})"
        }

        t.play()
        track = t
        return framesPerWrite
    }

    override fun write(buffer: ShortArray, frames: Int) {
        val t = track ?: error("write() called before open()")
        val shortsToWrite = frames * 2 // stereo
        var offset = 0
        // AudioTrack.write() may return short writes when the device is closing
        // or the track is paused; loop until everything is delivered or we're
        // told the track is gone.
        while (offset < shortsToWrite) {
            val written = t.write(buffer, offset, shortsToWrite - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                // ERROR_INVALID_OPERATION (-3), ERROR_BAD_VALUE (-2), ERROR_DEAD_OBJECT (-6),
                // or ERROR (-1). All are terminal for this session.
                error("AudioTrack.write returned error $written")
            }
            if (written == 0) {
                // Track no longer accepts data (paused/stopped from another path).
                return
            }
            offset += written
        }
    }

    override fun close() {
        val t = track ?: return
        track = null
        try {
            // STOPPED is the safe terminal state before release().
            if (t.playState != AudioTrack.PLAYSTATE_STOPPED) {
                t.stop()
            }
        } catch (_: IllegalStateException) {
            // Track was never playing or already torn down — fine, proceed to release.
        } finally {
            t.release()
        }
    }
}
