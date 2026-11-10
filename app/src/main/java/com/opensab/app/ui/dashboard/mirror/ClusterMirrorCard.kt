package com.opensab.app.ui.dashboard.mirror

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.SignalCellularOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opensab.app.R
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorDelivery
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorPopUp
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.mirror.MirrorState
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ActiveIndicator

/**
 * What the cluster is showing, and the zones that drive it: a semantic mirror, not a pictorial
 * replica. [MirrorPage.title] and [MirrorPage.lines] are painted verbatim (the very strings the
 * cluster received), while every label the app words itself comes from a
 * [com.opensab.cluster.mirror.MirrorActionToken].
 */
@Composable
fun ClusterMirrorCard(
    state: MirrorState,
    onAction: (FunctionId) -> Unit,
    onRequestHandover: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardDescription = stringResource(R.string.cd_mirror_card)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = cardDescription },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        when (state) {
            MirrorState.Idle -> IdleContent()

            is MirrorState.ClusterOwnsScreen -> ClusterOwnsScreenContent(
                bodyRes = state.bodyRes(),
                handoverPending = state.handoverPending,
                onRequestHandover = onRequestHandover
            )

            is MirrorState.Projecting -> ProjectingContent(state = state, onAction = onAction)
        }
    }
}

@Composable
private fun IdleContent() {
    Column(modifier = Modifier.padding(18.dp)) {
        Text(
            text = stringResource(R.string.mirror_state_idle_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.mirror_state_idle_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

/**
 * What the card says under its title. Once a wait for the screen has run out it suggests the one
 * thing that has released the screen before; it cannot say why the cluster kept it.
 */
@StringRes
internal fun MirrorState.ClusterOwnsScreen.bodyRes(): Int =
    if (grantOverdue && !handoverPending) {
        R.string.mirror_state_grant_overdue_body
    } else {
        R.string.mirror_state_cluster_owns_body
    }

/**
 * The native tachometer owns the screen, and the way back is an explicit button. A tap on the card
 * must never take the screen on its own: that would change what a moving rider has in front of them
 * without them asking.
 */
@Composable
private fun ClusterOwnsScreenContent(
    @StringRes bodyRes: Int,
    handoverPending: Boolean,
    onRequestHandover: () -> Unit
) {
    Column(modifier = Modifier.padding(18.dp)) {
        Text(
            text = stringResource(R.string.mirror_state_cluster_owns_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = onRequestHandover,
            enabled = !handoverPending
        ) {
            Text(
                text = stringResource(
                    if (handoverPending) {
                        R.string.mirror_state_handover_pending
                    } else {
                        R.string.mirror_state_request_handover
                    }
                )
            )
        }
    }
}

@Composable
private fun ProjectingContent(
    state: MirrorState.Projecting,
    onAction: (FunctionId) -> Unit
) {
    // While a modal is up, PopupManager.onInput swallows the press before the page ever sees it,
    // so the page zones have to be inert here too.
    val pageEnabled = state.popUp == null

    Column {
        MirrorHeader(
            page = state.page,
            enabled = pageEnabled,
            onAction = onAction
        )

        Column(modifier = Modifier.padding(18.dp)) {
            IndicatorBar(state.indicators)

            if (state.page.pageName == "MusicLoadingPage") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = state.page.accent.toComposeColor()
                    )
                }
            }

            state.page.lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            ZoneGrid(
                actions = state.page.actions,
                pageEnabled = pageEnabled,
                onAction = onAction
            )

            state.popUp?.let { popUp ->
                Spacer(modifier = Modifier.height(14.dp))
                ModalLayer(popUp = popUp, onAction = onAction)
            }

            Spacer(modifier = Modifier.height(14.dp))
            TrailRow(state.trail)
            Spacer(modifier = Modifier.height(6.dp))
            DeliveryRow(state.delivery)
        }
    }
}

/**
 * The header carries the accent the cluster received: the only chromatic trace the semantic mirror
 * keeps of the real panel. [MirrorSlot.HEADER] is reserved for the escape back to the carousel.
 */
@Composable
private fun MirrorHeader(
    page: MirrorPage,
    enabled: Boolean,
    onAction: (FunctionId) -> Unit
) {
    val accent = page.accent.toComposeColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = page.title.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        page.actions.firstOrNull { it.slot == MirrorSlot.HEADER }?.let { action ->
            ZoneButton(
                action = action,
                enabled = enabled && action.enabled,
                onAction = onAction
            )
        }
    }
}

@Composable
private fun IndicatorBar(indicators: Set<ActiveIndicator>) {
    if (indicators.isEmpty()) return
    Row(
        modifier = Modifier.padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        indicators.sortedBy { it.bit }.forEach { indicator ->
            Icon(
                imageVector = indicator.icon(),
                contentDescription = indicator.name,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * The five page slots, laid out the way the handlebar reaches them: the two arrows above, and the
 * left/primary/right triad below. A slot nobody declared simply is not drawn.
 */
@Composable
private fun ZoneGrid(
    actions: List<MirrorAction>,
    pageEnabled: Boolean,
    onAction: (FunctionId) -> Unit
) {
    val bySlot = actions.associateBy { it.slot }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ZoneRow(
            slots = listOf(MirrorSlot.UP, MirrorSlot.DOWN),
            bySlot = bySlot,
            pageEnabled = pageEnabled,
            onAction = onAction
        )
        ZoneRow(
            slots = listOf(MirrorSlot.LEFT, MirrorSlot.PRIMARY, MirrorSlot.RIGHT),
            bySlot = bySlot,
            pageEnabled = pageEnabled,
            onAction = onAction
        )
    }
}

@Composable
private fun ZoneRow(
    slots: List<MirrorSlot>,
    bySlot: Map<MirrorSlot, MirrorAction>,
    pageEnabled: Boolean,
    onAction: (FunctionId) -> Unit
) {
    val present = slots.mapNotNull { bySlot[it] }
    if (present.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        present.forEach { action ->
            ZoneButton(
                action = action,
                enabled = pageEnabled && action.enabled,
                onAction = onAction,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * One touch target. A tap emits exactly [MirrorAction.functionId] as a synthetic handlebar press;
 * a disabled zone is dimmed and emits nothing, which is how the card shows a control that is
 * declared but leads nowhere right now.
 */
@Composable
private fun ZoneButton(
    action: MirrorAction,
    enabled: Boolean,
    onAction: (FunctionId) -> Unit,
    modifier: Modifier = Modifier
) {
    val ui = action.token.ui()
    val label = stringResource(ui.labelRes)
    val description = stringResource(
        if (enabled) R.string.cd_mirror_action else R.string.cd_mirror_action_disabled,
        label
    )

    OutlinedButton(
        onClick = { onAction(action.functionId) },
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = description }
    ) {
        Icon(
            imageVector = ui.icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Both zones carry `DISMISS`, because that is what `PopupManager.onInput` does with either press.
 * The cluster paints hang-up and accept icons, but neither reaches the telecom adapter, and the
 * mirror does not promise what the engine does not do.
 */
@Composable
private fun ModalLayer(
    popUp: MirrorPopUp,
    onAction: (FunctionId) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = popUp.message,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.mirror_state_modal_active),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                popUp.actions.forEach { action ->
                    ZoneButton(
                        action = action,
                        enabled = action.enabled,
                        onAction = onAction,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** Diagnostic only: the cluster has no trail of its own, the app builds it from the stack. */
@Composable
private fun TrailRow(trail: List<String>) {
    if (trail.isEmpty()) return
    val separator = stringResource(R.string.mirror_state_trail_separator)
    val text = trail.joinToString(separator)
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics {
            contentDescription = text
        }
    )
}

@Composable
private fun DeliveryRow(delivery: MirrorDelivery) {
    val unknown = stringResource(R.string.mirror_delivery_error_position_unknown)
    val (colour, text) = when (delivery) {
        MirrorDelivery.InFlight ->
            MaterialTheme.colorScheme.outline to stringResource(R.string.mirror_delivery_in_flight)

        MirrorDelivery.Confirmed ->
            MaterialTheme.colorScheme.primary to stringResource(R.string.mirror_delivery_confirmed)

        MirrorDelivery.HandoverUnconfirmed ->
            MaterialTheme.colorScheme.tertiary to stringResource(R.string.mirror_delivery_handover_unconfirmed)

        is MirrorDelivery.Rejected -> MaterialTheme.colorScheme.error to stringResource(
            R.string.mirror_delivery_rejected,
            delivery.status,
            delivery.errorPosition?.toString() ?: unknown
        )

        MirrorDelivery.TimedOut ->
            MaterialTheme.colorScheme.error to stringResource(R.string.mirror_delivery_timed_out)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(colour, CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The header colour the cluster received, as the panel would have painted it. */
private fun ColorId.toComposeColor(): Color {
    val (red, green, blue) = defaultRgb
    return Color(red = red, green = green, blue = blue)
}

private fun ActiveIndicator.icon(): ImageVector = when (this) {
    ActiveIndicator.PHONE -> Icons.Filled.Phone
    ActiveIndicator.NAVIGATION -> Icons.Filled.Navigation
    ActiveIndicator.MUSIC -> Icons.Filled.MusicNote
    ActiveIndicator.MESSAGE -> Icons.AutoMirrored.Filled.Message
    ActiveIndicator.MUTE -> Icons.AutoMirrored.Filled.VolumeOff
    ActiveIndicator.NO_NETWORK -> Icons.Filled.SignalCellularOff
}
