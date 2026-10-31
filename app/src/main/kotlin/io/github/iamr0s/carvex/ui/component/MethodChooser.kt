@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.iamr0s.carvex.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.github.iamr0s.carvex.R
import androidx.compose.ui.window.Dialog
import io.github.iamr0s.carvex.ui.model.Method

/**
 * "How do you want to connect?" Methods turned off in Settings are shown but disabled.
 * Phones get a bottom sheet, tablets a centered dialog.
 */
@Composable
fun MethodChooser(
    deviceName: String,
    change: Boolean,
    allowed: Set<Method>,
    wide: Boolean,
    onPick: (Method) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = if (wide) 20.dp else 0.dp, bottom = 16.dp)) {
            Text(
                if (change) stringResource(R.string.change_method_for, deviceName)
                else stringResource(R.string.connect_to, deviceName),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.how_connect),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
            )
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
            ) {
                items(Method.entries, key = { it.name }) { m ->
                    MethodOption(m, enabled = m in allowed) { onPick(m) }
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }

    if (wide) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = cs.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 440.dp),
            ) { body() }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss) { body() }
    }
}

@Composable
private fun MethodOption(method: Method, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = cs.surfaceContainer,
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).alpha(if (enabled) 1f else 0.55f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = cs.secondaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(method.icon(), contentDescription = null, tint = cs.onSecondaryContainer)
                }
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(stringResource(method.labelRes), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (enabled) stringResource(method.hintRes) else stringResource(R.string.method_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

private fun Method.icon(): ImageVector = when (this) {
    Method.Bluetooth -> Icons.Default.Bluetooth
    Method.WiFi -> Icons.Default.Wifi
    Method.Usb -> Icons.Default.Usb
}
