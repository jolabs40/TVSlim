package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.support.SupportInvitation

/**
 * Support banner at the top of every tab, shown after a successful operation when `SupportController` decides.
 * It blocks nothing, and each of its three buttons closes it.
 */
@Composable
fun SupportBanner(
    visible: Boolean,
    onSupport: () -> Unit,
    onAlreadyDone: () -> Unit,
    onLater: () -> Unit,
) {
    if (!visible) return
    val links = LocalUriHandler.current
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                KofiSymbol(contentDescription = null)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.support_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(text = stringResource(R.string.support_text), style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(
                onClick = {
                    // Without a browser nothing breaks, and the banner closes as with Later.
                    runCatching { links.openUri(SupportInvitation.LINK) }
                    onSupport()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                KofiSymbol(contentDescription = null, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.support_donate))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onAlreadyDone) { Text(stringResource(R.string.support_already)) }
                TextButton(onClick = onLater) { Text(stringResource(R.string.support_later)) }
            }
        }
    }
}

/**
 * Ko-fi's cup symbol from its brand assets, untinted: its colors identify it, and its white inside keeps it readable
 * on the dark theme. Wider than tall, it fits a 24 dp icon box; each `drawable-*dpi` file is 24 dp wide.
 */
@Composable
internal fun KofiSymbol(contentDescription: String?, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Image(
        painter = painterResource(R.drawable.kofi_symbol),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
    )
}

/**
 * Top bar Ko-fi button, on every tab whether or not a TV is connected. Deliberately low-key: the banner is what
 * appears after a successful operation.
 */
@Composable
fun SupportButton() {
    val links = LocalUriHandler.current
    IconButton(onClick = { runCatching { links.openUri(SupportInvitation.LINK) } }) {
        KofiSymbol(contentDescription = stringResource(R.string.support_link))
    }
}
