package com.opensab.app.source.audio

import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.opensab.domain.audio.Cue
import com.opensab.domain.audio.CueChannel
import com.opensab.domain.audio.CueOutput
import com.opensab.domain.audio.CuePlayer
import com.opensab.domain.audio.CueRecipes
import com.opensab.domain.audio.GatedCuePlayer
import com.opensab.domain.audio.HeadsetPresence
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The platform half of [CuePlayer].
 *
 * **It asks for no audio focus.** No `requestAudioFocus`, no ducking: the cue mixes on top of
 * whatever is playing. The voice and the call already contend for focus, and a third holder asking
 * and releasing every 40 ms would reopen that race for nothing.
 *
 * `STREAM_NOTIFICATION` by way of [AudioAttributes] with the phone's own speaker: the cue is a
 * signal to the rider and not content, so it must not follow the music volume the handlebar has
 * just been used to lower. **With a headset connected it plays on the media channel instead**
 * ([CueChannel]): silent mode and Do Not Disturb mute the notification stream, and the rider heard
 * nothing in the helmet. The route is read at every [play], so a headset connected mid-session
 * takes the next cue.
 *
 * `MODE_STATIC`, one track per cue and channel, all synthesised once and written into their buffers
 * at construction: firing is then `stop()` + `reloadStaticData()` + `play()`, with nothing to
 * synthesise and nothing to allocate. Eighteen buffers under 200 ms at 22 050 Hz are about 80 KB
 * in total; what is not measured is whether some device caps the number of open tracks, and the way
 * back if it does is one shared `MODE_STREAM` track at the cost of latency.
 *
 * It throws nothing of its own on purpose: containment is owned by [GatedCuePlayer].
 *
 * As [HeadsetPresence] it answers the same "is a headset connected" [play] routes by, from an
 * `AudioDeviceCallback`, so the link cue can wait for the intercom to join.
 */
class AndroidCuePlayer(
    private val audioManager: AudioManager,
    private val notificationManager: NotificationManager
) : CuePlayer, CueOutput, HeadsetPresence, AutoCloseable {

    private val attributes: Map<CueChannel, AudioAttributes> = mapOf(
        CueChannel.NOTIFICATION to AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
            .build(),
        CueChannel.MEDIA to AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    )

    private val tracks: Map<CueChannel, Map<Cue, AudioTrack>> = CueChannel.entries.associateWith { channel ->
        Cue.entries.associateWith { trackFor(it, attributes.getValue(channel)) }
    }

    override fun play(cue: Cue) {
        val track = tracks.getValue(channel()).getValue(cue)
        track.stop()
        track.reloadStaticData()
        track.play()
    }

    override fun connected(): Flow<Boolean> = callbackFlow {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                trySend(headsetConnected())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                trySend(headsetConnected())
            }
        }
        trySend(headsetConnected())
        audioManager.registerAudioDeviceCallback(callback, null)
        awaitClose { audioManager.unregisterAudioDeviceCallback(callback) }
    }.distinctUntilChanged()

    override fun isSilenced(): Boolean {
        val stream = streamOf(channel())
        return audioManager.getStreamVolume(stream) == 0 || audioManager.isStreamMute(stream)
    }

    private fun streamOf(channel: CueChannel): Int = when (channel) {
        CueChannel.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        CueChannel.MEDIA -> AudioManager.STREAM_MUSIC
    }

    private fun channel(): CueChannel = CueChannel.forRoute(headsetConnected())

    private fun headsetConnected(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type in HEADSET_TYPES ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

    /**
     * The channel the next cue takes, the route the platform would pick for it, that stream's volume
     * and mute, and the interruption filter — Do Not Disturb silences sonification below this class, so
     * the log carries the facts and not a guess about what the rider heard.
     */
    override fun describe(): String {
        val channel = channel()
        val stream = streamOf(channel)
        return "%s on the %s channel (volume %d/%d, muted=%b, interruptions=%s)".format(
            route(attributes.getValue(channel)),
            channel,
            audioManager.getStreamVolume(stream),
            audioManager.getStreamMaxVolume(stream),
            audioManager.isStreamMute(stream),
            interruptionFilterName(notificationManager.currentInterruptionFilter)
        )
    }

    override fun close() = tracks.values.flatMap { it.values }.forEach { it.release() }

    private fun route(attributes: AudioAttributes): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "route unknown before API 33"
        val devices = try {
            audioManager.getAudioDevicesForAttributes(attributes)
        } catch (e: SecurityException) {
            return "route unknown"
        }
        if (devices.isEmpty()) return "no output device"
        return devices.joinToString(" + ") { "${deviceTypeName(it.type)} ${it.productName}" }
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "speaker-safe"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt-a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt-sco"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble-headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired-headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired-headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb-headset"
        else -> "device-type-$type"
    }

    private fun interruptionFilterName(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "ALL"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "NONE"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS"
        else -> "UNKNOWN($filter)"
    }

    private fun trackFor(cue: Cue, attributes: AudioAttributes): AudioTrack {
        val samples = CueRecipes.samplesFor(cue)
        val bytes = samples.size * Short.SIZE_BYTES
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(CueRecipes.SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size)
        return track
    }

    private companion object {
        /** Outputs worn by the rider. */
        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
    }
}
