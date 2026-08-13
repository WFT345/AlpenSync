package app.alpensync.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.alpensync.R
import app.alpensync.ui.theme.AlpenFg

@Composable
fun LegalPane(kind: LegalKind, onBack: () -> Unit) {
    val context = LocalContext.current
    val blocks = remember(kind) { loadLegalBlocks(context, kind) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlpenLockup(kind.title)
            AlpenTextLink(label = stringResource(R.string.legal_close), onClick = onBack)
        }
        AlpenVSpace(16)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            blocks.forEach { LegalBlockView(it) }
            AlpenVSpace(24)
        }
    }
}

@Composable
fun LegalLinks(onShow: (LegalKind) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlpenTextLink(label = stringResource(R.string.legal_privacy), onClick = { onShow(LegalKind.PRIVACY) })
        AlpenTextLink(label = stringResource(R.string.legal_terms), onClick = { onShow(LegalKind.TERMS) })
    }
}

@Composable
private fun LegalBlockView(block: LegalBlock) {
    when (block) {
        is LegalBlock.Title -> AlpenTitle(block.text)
        is LegalBlock.Heading -> AlpenLockup(block.text)
        is LegalBlock.Paragraph -> Text(
            text = block.text,
            color = AlpenFg.copy(alpha = 0.86f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun loadLegalBlocks(context: Context, kind: LegalKind): List<LegalBlock> =
    context.assets.open(kind.assetPath).bufferedReader().use { parseLegalMarkdown(it.readText()) }
