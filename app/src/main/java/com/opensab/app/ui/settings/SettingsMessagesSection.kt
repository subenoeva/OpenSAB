package com.opensab.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opensab.app.R
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.InboundMessageMode
import com.opensab.domain.settings.Tunable

@Composable
internal fun MessagesSection(
    settings: AppSettings,
    onIntent: (SettingsUiIntent) -> Unit,
    onEditQuickReplies: () -> Unit,
    onEditTunable: (Tunable) -> Unit
) {
    val defaults = stringArrayResource(R.array.default_quick_replies).toList()
    val repliesInUse = settings.quickRepliesInUse(defaults)

    SettingsCategory(
        title = stringResource(R.string.settings_category_messages),
        icon = Icons.AutoMirrored.Filled.Chat
    ) {
        SettingsSegmentedTabSelector(
            title = stringResource(R.string.settings_inbound_messages_title),
            subtitle = when (settings.inboundMessageMode) {
                InboundMessageMode.PROMPT -> stringResource(R.string.settings_inbound_messages_prompt_desc)
                InboundMessageMode.READ_DIRECTLY -> stringResource(R.string.settings_inbound_messages_read_desc)
                InboundMessageMode.IGNORE -> stringResource(R.string.settings_inbound_messages_ignore_desc)
            },
            options = InboundMessageMode.entries,
            selected = settings.inboundMessageMode,
            optionLabel = { mode ->
                when (mode) {
                    InboundMessageMode.PROMPT -> stringResource(R.string.settings_inbound_messages_prompt)
                    InboundMessageMode.READ_DIRECTLY -> stringResource(R.string.settings_inbound_messages_read)
                    InboundMessageMode.IGNORE -> stringResource(R.string.settings_inbound_messages_ignore)
                }
            },
            optionIcon = { mode ->
                when (mode) {
                    InboundMessageMode.PROMPT -> Icons.Default.SupportAgent
                    InboundMessageMode.READ_DIRECTLY -> Icons.Default.RecordVoiceOver
                    InboundMessageMode.IGNORE -> Icons.Default.NotificationsOff
                }
            },
            onSelect = { mode ->
                onIntent(SettingsUiIntent.UpdateSettings {
                    it.copy(inboundMessageMode = mode)
                })
            }
        )
        SettingsValueSelectorItem(
            title = stringResource(R.string.settings_quick_replies_title),
            value = repliesInUse?.toString() ?: stringResource(R.string.settings_quick_replies_empty),
            subtitle = stringResource(R.string.settings_quick_replies_desc),
            onClick = onEditQuickReplies
        )
        SettingsTunableItem(Tunable.REPLY_SENT_LINGER, settings, onEditTunable)
    }
}

/**
 * A fixed number of fields: four is what one cluster menu shows. Emptying a field removes that
 * reply ([resolveQuickReplies] drops blanks), and emptying all of them restores the defaults.
 */
@Composable
internal fun QuickRepliesDialog(
    initial: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val slots = remember(initial) {
        List(QUICK_REPLY_SLOTS) { index -> initial.getOrElse(index) { "" } }
            .map { mutableStateOf(it) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_quick_replies_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.settings_quick_replies_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                slots.forEachIndexed { index, slot ->
                    OutlinedTextField(
                        value = slot.value,
                        onValueChange = { slot.value = it },
                        label = {
                            Text(stringResource(R.string.settings_quick_replies_hint, index + 1))
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(slots.map { it.value.trim() }) }) {
                Text(stringResource(R.string.dialog_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_action_cancel)) }
        }
    )
}

private const val QUICK_REPLY_SLOTS = 4
