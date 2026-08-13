package app.alpensync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.alpensync.ui.theme.AlpenBg2
import app.alpensync.ui.theme.AlpenError
import app.alpensync.ui.theme.AlpenFg
import app.alpensync.ui.theme.AlpenIce
import app.alpensync.ui.theme.AlpenRule

val AlpenRound = RoundedCornerShape(16.dp)
val AlpenFieldRound = RoundedCornerShape(14.dp)
val AlpenPill = RoundedCornerShape(50)

@Composable
fun AlpenCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .clip(AlpenRound)
            .background(AlpenBg2, AlpenRound)
            .border(1.dp, AlpenRule, AlpenRound)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
fun AlpenBadge(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    if (tone == StatusTone.NONE) return
    val ink = badgeInk(tone)
    Row(
        modifier = modifier
            .clip(AlpenPill)
            .background(ink.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (tone == StatusTone.BUSY) {
            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = ink)
        } else {
            Box(Modifier.size(6.dp).background(ink, CircleShape))
        }
        Text(text = text, color = ink, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun AlpenRuleLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(AlpenRule))
}

@Composable
fun AlpenFact(label: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AlpenLockup(label)
        AlpenBody(body)
    }
}

private fun badgeInk(tone: StatusTone): Color = when (tone) {
    StatusTone.OK, StatusTone.BUSY, StatusTone.ATTENTION -> AlpenIce
    StatusTone.PROBLEM -> AlpenError
    StatusTone.NONE -> AlpenFg
}
