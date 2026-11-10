package com.opensab.app.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.opensab.app.R
import com.opensab.app.theme.OpenSABTheme
import com.opensab.sab.session.vehicle.ClusterDisplay

/**
 * Asked on the first pairing because nothing over the link says whether the cluster paints colour.
 */
@Composable
internal fun ClusterQuestionDialog(
    onAnswer: (ClusterDisplay) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pairing_cluster_question_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAnswer(ClusterDisplay.COLOR) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.pairing_cluster_color)) }
                Button(
                    onClick = { onAnswer(ClusterDisplay.SEGMENT) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.pairing_cluster_segment)) }
                Text(
                    text = stringResource(R.string.pairing_cluster_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_action_cancel)) }
        }
    )
}

@Preview(name = "Cluster question", showBackground = true)
@Composable
private fun ClusterQuestionDialogPreview() {
    OpenSABTheme {
        ClusterQuestionDialog(onAnswer = {}, onDismiss = {})
    }
}
