package io.github.iamr0s.carvex.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.iamr0s.carvex.R

/** Localized relative time used by the devices page. */
@Composable
fun agoText(then: Long, now: Long): String {
    val seconds = ((now - then) / 1000).coerceAtLeast(0)
    return when {
        seconds < 5 -> stringResource(R.string.just_now)
        seconds < 60 -> stringResource(R.string.seconds_ago, seconds)
        else -> stringResource(R.string.minutes_ago, seconds / 60)
    }
}
