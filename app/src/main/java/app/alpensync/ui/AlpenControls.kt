package app.alpensync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.alpensync.R
import app.alpensync.ui.theme.AlpenBg
import app.alpensync.ui.theme.AlpenBg2
import app.alpensync.ui.theme.AlpenFg
import app.alpensync.ui.theme.AlpenIce
import app.alpensync.ui.theme.AlpenMute
import app.alpensync.ui.theme.AlpenRule

@Composable
fun AlpenWordmark(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.app_name).uppercase(),
        modifier = modifier,
        color = AlpenFg,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
fun AlpenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        color = AlpenFg,
        style = MaterialTheme.typography.headlineMedium,
    )
}

@Composable
fun AlpenLockup(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = AlpenIce,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
fun AlpenBody(text: String, modifier: Modifier = Modifier, mute: Boolean = false) {
    Text(
        text = text,
        modifier = modifier,
        color = if (mute) AlpenMute else AlpenFg.copy(alpha = 0.86f),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
fun AlpenPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = AlpenFieldRound,
        colors = ButtonDefaults.buttonColors(
            containerColor = AlpenFg,
            contentColor = AlpenBg,
            disabledContainerColor = AlpenFg.copy(alpha = 0.28f),
            disabledContentColor = AlpenBg.copy(alpha = 0.6f),
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = AlpenBg,
            )
        } else {
            Text(text = label.uppercase(), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun AlpenTextLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(
            text = label.uppercase(),
            color = AlpenMute,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
fun AlpenLineField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AlpenLockup(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = AlpenFg),
            cursorBrush = SolidColor(AlpenIce),
            keyboardOptions = keyboardOptions,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .fieldChrome(),
        )
    }
}

@Composable
fun AlpenSecureField(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AlpenLockup(label)
        BasicSecureTextField(
            state = state,
            enabled = enabled,
            textObfuscationMode = TextObfuscationMode.RevealLastTyped,
            keyboardOptions = keyboardOptions,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = AlpenFg),
            cursorBrush = SolidColor(AlpenIce),
            modifier = Modifier
                .fillMaxWidth()
                .fieldChrome(),
        )
    }
}

@Composable
fun AlpenChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fill = if (selected) AlpenFg else Color.Transparent
    val ink = if (selected) AlpenBg else AlpenFg
    Box(
        modifier = modifier
            .clip(AlpenPill)
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = ink,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, letterSpacing = 0.sp),
        )
    }
}

@Composable
fun AlpenQuietRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AlpenLockup(label)
        Text(text = value, color = AlpenFg, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun AlpenScreenHead(hero: String, lockup: String? = null) {
    AlpenWordmark()
    AlpenVSpace(28)
    AlpenTitle(hero)
    if (!lockup.isNullOrBlank()) {
        AlpenVSpace(10)
        AlpenLockup(lockup)
    }
}

@Composable
fun AlpenPane(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), content = content)
}

@Composable
fun AlpenVSpace(height: Int) {
    Spacer(Modifier.height(height.dp))
}

private fun Modifier.fieldChrome(): Modifier = this
    .clip(AlpenFieldRound)
    .background(AlpenBg2, AlpenFieldRound)
    .border(1.dp, AlpenRule, AlpenFieldRound)
    .padding(horizontal = 16.dp, vertical = 14.dp)
