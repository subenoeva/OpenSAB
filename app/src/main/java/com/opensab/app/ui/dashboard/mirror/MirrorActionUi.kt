package com.opensab.app.ui.dashboard.mirror

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.ui.graphics.vector.ImageVector
import com.opensab.app.R
import com.opensab.cluster.mirror.MirrorActionToken

/**
 * The **only** place the mirror acquires a language. `MirrorPage.title` and `MirrorPage.lines` are
 * painted verbatim because they are the very strings the cluster received, while everything the
 * app words itself arrives here as a token and picks up its label and icon.
 */
data class MirrorActionUi(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector
)

/**
 * Exhaustive over [MirrorActionToken] and deliberately without an `else` branch: a token added to
 * the engine must not compile until somebody has given it a label the rider can read.
 */
fun MirrorActionToken.ui(): MirrorActionUi = when (this) {
    MirrorActionToken.CAROUSEL_PREVIOUS ->
        MirrorActionUi(R.string.mirror_action_carousel_previous, Icons.Filled.ExpandLess)

    MirrorActionToken.CAROUSEL_NEXT ->
        MirrorActionUi(R.string.mirror_action_carousel_next, Icons.Filled.ExpandMore)

    MirrorActionToken.OPEN ->
        MirrorActionUi(R.string.mirror_action_open, Icons.Filled.Check)

    MirrorActionToken.GO_BACK ->
        MirrorActionUi(R.string.mirror_action_go_back, Icons.AutoMirrored.Filled.ArrowBack)

    MirrorActionToken.OPEN_ROOT_MENU ->
        MirrorActionUi(R.string.mirror_action_open_root_menu, Icons.Filled.Menu)

    MirrorActionToken.PLAY ->
        MirrorActionUi(R.string.mirror_action_play, Icons.Filled.PlayArrow)

    MirrorActionToken.PAUSE ->
        MirrorActionUi(R.string.mirror_action_pause, Icons.Filled.Pause)

    MirrorActionToken.NEXT_TRACK ->
        MirrorActionUi(R.string.mirror_action_next_track, Icons.Filled.SkipNext)

    MirrorActionToken.VOLUME_UP ->
        MirrorActionUi(R.string.mirror_action_volume_up, Icons.AutoMirrored.Filled.VolumeUp)

    MirrorActionToken.VOLUME_DOWN ->
        MirrorActionUi(R.string.mirror_action_volume_down, Icons.AutoMirrored.Filled.VolumeDown)

    MirrorActionToken.VOLUME_MUTE ->
        MirrorActionUi(R.string.mirror_action_volume_mute, Icons.AutoMirrored.Filled.VolumeOff)

    MirrorActionToken.ACCEPT_CALL ->
        MirrorActionUi(R.string.mirror_action_accept_call, Icons.Filled.Call)

    MirrorActionToken.REJECT_CALL ->
        MirrorActionUi(R.string.mirror_action_reject_call, Icons.Filled.CallEnd)

    MirrorActionToken.HANG_UP ->
        MirrorActionUi(R.string.mirror_action_hang_up, Icons.Filled.CallEnd)

    MirrorActionToken.PLACE_CALL ->
        MirrorActionUi(R.string.mirror_action_place_call, Icons.Filled.Call)

    MirrorActionToken.RETRY_CALL ->
        MirrorActionUi(R.string.mirror_action_retry_call, Icons.Filled.Replay)

    MirrorActionToken.REPEAT_MANEUVER ->
        MirrorActionUi(R.string.mirror_action_repeat_maneuver, Icons.Filled.Replay)

    MirrorActionToken.REPEAT_FORECAST ->
        MirrorActionUi(R.string.mirror_action_repeat_forecast, Icons.Filled.Replay)

    MirrorActionToken.REFRESH_WEATHER ->
        MirrorActionUi(R.string.mirror_action_refresh_weather, Icons.Filled.Refresh)

    MirrorActionToken.READ_MESSAGE ->
        MirrorActionUi(R.string.mirror_action_read_message, Icons.Filled.RecordVoiceOver)

    MirrorActionToken.DISMISS_MESSAGE ->
        MirrorActionUi(R.string.mirror_action_dismiss_message, Icons.Filled.Close)

    MirrorActionToken.SEND_MESSAGE ->
        MirrorActionUi(R.string.mirror_action_send_message, Icons.AutoMirrored.Filled.Send)

    MirrorActionToken.RETRY_DICTATION ->
        MirrorActionUi(R.string.mirror_action_retry_dictation, Icons.Filled.Replay)

    MirrorActionToken.CANCEL_DICTATION ->
        MirrorActionUi(R.string.mirror_action_cancel_dictation, Icons.Filled.Close)

    MirrorActionToken.REPLY_MESSAGE ->
        MirrorActionUi(R.string.mirror_action_reply_message, Icons.AutoMirrored.Filled.Reply)

    MirrorActionToken.QUICK_REPLIES ->
        MirrorActionUi(R.string.mirror_action_quick_replies, Icons.AutoMirrored.Filled.Reply)

    MirrorActionToken.DISMISS ->
        MirrorActionUi(R.string.mirror_action_dismiss, Icons.Filled.Close)
}
