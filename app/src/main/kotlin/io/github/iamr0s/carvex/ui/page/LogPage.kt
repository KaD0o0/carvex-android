package io.github.iamr0s.carvex.ui.page

import io.github.iamr0s.carvex.ui.component.*
import io.github.iamr0s.carvex.ui.model.*
import io.github.iamr0s.carvex.ui.screen.Nav
import io.github.iamr0s.carvex.ui.theme.AppColors
import io.github.iamr0s.carvex.ui.theme.ExtraColors

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.iamr0s.carvex.ui.model.DeviceViewModel
import io.github.iamr0s.carvex.ui.model.LogGroup
import io.github.iamr0s.carvex.ui.model.UiState
import io.github.iamr0s.carvex.R
import io.github.iamr0s.carvex.ui.tool.LocalAppInsets
import io.github.iamr0s.carvex.ui.tool.toPaddingValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LogPage(
    state: UiState,
    vm: DeviceViewModel,
    nav: Nav,
    showBack: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportedMessage = stringResource(R.string.log_exported)
    val exportFailedMessage = stringResource(R.string.log_export_failed)
    val copiedMessage = stringResource(R.string.log_copied)
    val x = AppColors.extra
    val safe = LocalAppInsets.current.content.toPaddingValues()
    val direction = LocalLayoutDirection.current

    // Export: the system file picker lets the user choose where to save the .txt file.
    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = vm.logText()
                    withContext(Dispatchers.IO) {
                        checkNotNull(context.contentResolver.openOutputStream(uri)).use {
                            it.write(text.toByteArray(Charsets.UTF_8))
                        }
                    }
                    vm.say(exportedMessage)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    vm.say(exportFailedMessage)
                }
            }
        }
    }

    Column(
        modifier.fillMaxSize().padding(
            start = safe.calculateLeftPadding(direction),
            top = safe.calculateTopPadding(),
            end = safe.calculateRightPadding(direction),
            bottom = safe.calculateBottomPadding(),
        ),
    ) {
        ScreenTitle(stringResource(R.string.screen_logs), onBack = if (showBack) nav.back else null) {
            FilledTonalIconButton(onClick = {
                scope.launch {
                    try {
                        val text = vm.logText()
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("device-log", text))
                        vm.say(copiedMessage)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        vm.say(exportFailedMessage)
                    }
                }
            }) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.action_copy),
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { export.launch("device-log.txt") }) {
                Icon(
                    Icons.Default.FileDownload,
                    contentDescription = stringResource(R.string.action_export),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.logs_hint),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val allClosed = state.logs.all { !it.open }
            TextButton(onClick = { vm.setAllGroups(allClosed) }) {
                Text(stringResource(if (allClosed) R.string.unfold_all else R.string.fold_all))
            }
        }

        LazyColumn(
            Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(MaterialTheme.shapes.large)
                .background(x.logBg),
        ) {
            items(state.logs, key = { it.id }) { group ->
                LogGroupView(group, x) { vm.toggleGroup(group.id) }
            }
        }
    }
}

@Composable
private fun LogGroupView(group: LogGroup, x: ExtraColors, onToggle: () -> Unit) {
    val angle by animateFloatAsState(if (group.open) 0f else -90f, tween(250), label = "chevron")
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(x.logHeader)
                .clickable(
                    onClickLabel = stringResource(if (group.open) R.string.action_fold else R.string.action_unfold),
                    onClick = onToggle,
                )
                .heightIn(min = 44.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.rotate(angle), tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(
                "${group.time} ${group.title}",
                modifier = Modifier.weight(1f),
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            )
            Text("${group.lines.size}", color = x.logDim, fontSize = 13.sp)
        }
        AnimatedVisibility(visible = group.open) {
            Text(
                text = groupText(group, x),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 21.sp,
                color = x.logText,
                softWrap = false, // long lines stay on one line...
                modifier = Modifier
                    .horizontalScroll(rememberScrollState()) // ...and scroll sideways
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

private fun groupText(group: LogGroup, x: ExtraColors): AnnotatedString = buildAnnotatedString {
    group.lines.forEachIndexed { i, line ->
        if (i > 0) append('\n')
        withStyle(SpanStyle(color = x.logDim)) { append(line.time) }
        append(' ')
        val levelColor = when (line.level) {
            "WARN" -> x.warn
            "ERROR" -> x.bad
            else -> x.info
        }
        withStyle(SpanStyle(color = levelColor)) { append(line.level.padEnd(5)) }
        append(line.message)
    }
}
