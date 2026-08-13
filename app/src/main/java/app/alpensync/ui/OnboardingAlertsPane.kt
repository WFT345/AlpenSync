package app.alpensync.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.alpensync.R
import app.alpensync.contacts.sync.RelinkAlertPrefs
import app.alpensync.ui.theme.AlpenIce

@Composable
internal fun OnboardingAlertsPane(onFinished: () -> Unit) {
    val context = LocalContext.current
    val prefs = RelinkAlertPrefs(context)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onFinished() }
    AlpenPane {
        PrimerCopy()
        AlpenVSpace(28)
        PrimerMark()
        AlpenVSpace(28)
        AlpenCard(Modifier.fillMaxWidth()) {
            AlpenFact(
                label = stringResource(R.string.onboarding_alerts_fact_label),
                body = stringResource(R.string.onboarding_alerts_fact_body),
            )
        }
        Spacer(Modifier.weight(1f, fill = true))
        AlpenPrimaryButton(
            label = stringResource(R.string.onboarding_alerts_allow),
            onClick = {
                prefs.accept()
                if (Build.VERSION.SDK_INT >= 33) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onFinished()
                }
            },
        )
        AlpenTextLink(
            label = stringResource(R.string.onboarding_alerts_skip),
            onClick = {
                prefs.decline()
                onFinished()
            },
        )
    }
}

@Composable
private fun PrimerCopy() {
    AlpenWordmark()
    AlpenVSpace(28)
    AlpenLockup(stringResource(R.string.onboarding_alerts_kicker))
    AlpenVSpace(12)
    AlpenTitle(stringResource(R.string.onboarding_alerts_title))
    AlpenVSpace(10)
    AlpenBody(stringResource(R.string.onboarding_alerts_lede), mute = true)
}

@Composable
private fun PrimerMark() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(64.dp)) {
            val stroke = Stroke(width = size.width * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val cx = size.width / 2f
            val span = size.width * 0.34f
            listOf(0.34f, 0.62f).forEach { yFrac ->
                val y = size.height * yFrac
                val path = Path().apply {
                    moveTo(cx - span, y)
                    lineTo(cx, y - size.height * 0.2f)
                    lineTo(cx + span, y)
                }
                drawPath(path, AlpenIce, style = stroke)
            }
        }
    }
}
