package app.alpensync.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.alpensync.R
import app.alpensync.ui.theme.AlpenBg

@Composable
internal fun WelcomePane(onContinue: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.welcome_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.35f to Color.Transparent,
                        1f to AlpenBg,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            AlpenWordmark()
            Spacer(Modifier.weight(1f, fill = true))
            WelcomeCopy()
            AlpenVSpace(28)
            AlpenPrimaryButton(
                label = stringResource(R.string.welcome_continue),
                onClick = onContinue,
            )
        }
    }
}

@Composable
private fun WelcomeCopy() {
    AlpenTitle(stringResource(R.string.welcome_title))
    AlpenVSpace(12)
    AlpenBody(stringResource(R.string.welcome_lede))
    AlpenVSpace(20)
    AlpenFact(
        label = stringResource(R.string.welcome_push_label),
        body = stringResource(R.string.welcome_push_body),
    )
    AlpenVSpace(16)
    AlpenFact(
        label = stringResource(R.string.welcome_pull_label),
        body = stringResource(R.string.welcome_pull_body),
    )
}
