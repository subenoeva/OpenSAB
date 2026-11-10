package com.opensab.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class CueChannelTest {

    /**
     * With a headset on, the rider hears the cue in the helmet, and a phone in silent mode or Do
     * Not Disturb silences the notification stream: cues go out on the media channel instead.
     */
    @Test
    fun aConnectedHeadsetSendsCuesThroughTheMediaChannel() {
        assertEquals(CueChannel.MEDIA, CueChannel.forRoute(headsetConnected = true))
    }

    /** Without one the cue comes out of the phone, where the notification stream is the right one. */
    @Test
    fun withoutAHeadsetCuesStayOnTheNotificationChannel() {
        assertEquals(CueChannel.NOTIFICATION, CueChannel.forRoute(headsetConnected = false))
    }
}
