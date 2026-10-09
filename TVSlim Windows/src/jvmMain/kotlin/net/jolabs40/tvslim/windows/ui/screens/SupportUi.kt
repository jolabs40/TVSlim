package net.jolabs40.tvslim.windows.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.about_support
import net.jolabs40.tvslim.windows.resources.kofi_symbol
import net.jolabs40.tvslim.windows.resources.support_already
import net.jolabs40.tvslim.windows.resources.support_donate
import net.jolabs40.tvslim.windows.resources.support_later
import net.jolabs40.tvslim.windows.resources.support_text
import net.jolabs40.tvslim.windows.resources.support_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Ko-fi's cup symbol as published in its brand assets, untinted: its colors make it recognizable and its white
 * inside keeps it readable on the dark theme. Wider than tall, it fits a 24 dp icon box; the files are scaled to
 * 24 dp wide in each `drawable-*dpi`.
 */
@Composable
fun KofiSymbol(contentDescription: String?, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Image(
        painter = painterResource(Res.drawable.kofi_symbol),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
    )
}

/**
 * Ko-fi button in the top bar, on every tab, opening the support page. Deliberately quiet: nothing blinks or
 * reappears on its own; thanking after a successful action is the banner's job.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SupportButton(onClick: () -> Unit) {
    val label = stringResource(Res.string.about_support)
    TooltipArea(
        tooltip = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        },
    ) {
        IconButton(onClick = onClick) {
            KofiSymbol(contentDescription = label)
        }
    }
}

/**
 * Support banner, below the update banner. Shown after a successful action, only when `SupportController` decides.
 * It blocks nothing, and each of its three buttons dismisses it.
 */
@Composable
fun SupportBanner(
    visible: Boolean,
    onSupport: () -> Unit,
    onAlreadyDone: () -> Unit,
    onLater: () -> Unit,
) {
    if (!visible) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KofiSymbol(contentDescription = null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(Res.string.support_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = stringResource(Res.string.support_text), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onAlreadyDone) { Text(stringResource(Res.string.support_already)) }
            TextButton(onClick = onLater) { Text(stringResource(Res.string.support_later)) }
            Button(onClick = onSupport) {
                KofiSymbol(contentDescription = null, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.support_donate))
            }
        }
    }
}
