package app.alpensync.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.alpensync.R
import app.alpensync.ui.theme.AlpenBg
import app.alpensync.ui.theme.AlpenFg
import app.alpensync.ui.theme.AlpenIce
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SPLASH_MS = 1600
private val CHEVRON_DELAYS = listOf(0, 90, 180)

@Composable
fun SplashPane(onFinished: () -> Unit) {
    val lifts = remember { List(3) { Animatable(14f) } }
    val fades = remember { List(3) { Animatable(0f) } }
    val word = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        lifts.indices.forEach { i ->
            launch {
                delay(CHEVRON_DELAYS[i].toLong())
                launch { lifts[i].animateTo(0f, tween(520, easing = FastOutSlowInEasing)) }
                fades[i].animateTo(1f, tween(420))
            }
        }
        delay(420)
        word.animateTo(1f, tween(380))
        delay((SPLASH_MS - 420).toLong())
        onFinished()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AlpenBg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            UploadMark(
                lifts = lifts.map { it.value },
                fades = fades.map { it.value },
            )
            AlpenVSpace(22)
            Text(
                text = stringResource(R.string.app_name).uppercase(),
                color = AlpenFg,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.graphicsLayer { alpha = word.value },
            )
        }
    }
}

@Composable
private fun UploadMark(lifts: List<Float>, fades: List<Float>) {
    Canvas(Modifier.size(72.dp)) {
        val stroke = Stroke(width = size.width * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val cx = size.width / 2f
        val span = size.width * 0.34f
        val rows = listOf(0.28f, 0.52f, 0.76f)
        rows.forEachIndexed { i, yFrac ->
            val y = size.height * yFrac + lifts[i]
            val path = Path().apply {
                moveTo(cx - span, y)
                lineTo(cx, y - size.height * 0.16f)
                lineTo(cx + span, y)
            }
            drawPath(path, AlpenIce.copy(alpha = fades[i]), style = stroke)
            if (i == 0) {
                drawCircle(
                    color = AlpenIce.copy(alpha = fades[i] * 0.35f),
                    radius = size.minDimension * 0.035f,
                    center = Offset(cx, y - size.height * 0.18f),
                )
            }
        }
    }
}
