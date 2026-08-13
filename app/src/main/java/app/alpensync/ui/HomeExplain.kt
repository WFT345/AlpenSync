package app.alpensync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.alpensync.R
import app.alpensync.ui.theme.AlpenMute

@Composable
internal fun HomeIntro(status: HomeStatus) {
    HomeTopBar(status)
    AlpenVSpace(16)
    AlpenTitle(stringResource(R.string.home_title))
    AlpenVSpace(6)
    AlpenBody(stringResource(R.string.home_lede), mute = true)
    nextStep(status)?.let {
        AlpenVSpace(8)
        AlpenBody(it)
    }
}

@Composable
internal fun HowItWorksToggle() {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlpenLockup(stringResource(R.string.home_how_title))
            Text(
                text = if (open) "–" else "+",
                color = AlpenMute,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        AnimatedVisibility(visible = open) {
            AlpenCard(Modifier.fillMaxWidth()) {
                AlpenFact(
                    label = stringResource(R.string.home_push_label),
                    body = stringResource(R.string.home_push_body),
                )
                AlpenRuleLine()
                AlpenFact(
                    label = stringResource(R.string.home_pull_label),
                    body = stringResource(R.string.home_pull_body),
                )
            }
        }
    }
}

@Composable
private fun nextStep(status: HomeStatus): String? = when (status.headline) {
    HomeHeadline.NEEDS_ACCESS -> stringResource(R.string.home_next_grant)
    HomeHeadline.READY -> stringResource(R.string.home_next_pull)
    HomeHeadline.NEEDS_RELINK -> stringResource(R.string.relink_lede)
    HomeHeadline.CANT_START -> stringResource(R.string.home_cant_start_lockup)
    else -> null
}
