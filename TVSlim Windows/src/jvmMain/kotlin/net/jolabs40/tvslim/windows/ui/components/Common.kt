package net.jolabs40.tvslim.windows.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.catalog.Risk
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.baseline_connected_tv_24
import net.jolabs40.tvslim.windows.resources.risk_high
import net.jolabs40.tvslim.windows.resources.risk_low
import net.jolabs40.tvslim.windows.resources.risk_medium
import net.jolabs40.tvslim.windows.resources.risk_none
import net.jolabs40.tvslim.windows.ui.UiMessage
import net.jolabs40.tvslim.windows.ui.phrase
import net.jolabs40.tvslim.windows.ui.theme.RiskColors
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Titled card used by most screens, as on the phone. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.cardColors(),
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth(), colors = colors) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
fun ValueRow(label: String, rawValue: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = rawValue, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SecondaryText(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text = text,
        modifier = modifier,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun Gauge(rawValue: Long, total: Long, modifier: Modifier = Modifier) {
    val fraction = if (total > 0) (rawValue.toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
    }
}

/** Placeholder for tabs that have nothing to show without a TV. */
@Composable
fun EmptyScreen(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(Res.drawable.baseline_connected_tv_24),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = text,
                modifier = Modifier.widthIn(max = 420.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Two independently scrolling columns to use the desktop's width; a narrow window stacks them in phone order. */
@Composable
fun TwoColumns(
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth < 760.dp) {
            Row(modifier = Modifier.fillMaxSize()) {
                ScrollingColumn(weight = 1f) {
                    left()
                    right()
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                ScrollingColumn(weight = 1f, content = left)
                VerticalDivider()
                ScrollingColumn(weight = 1f, content = right)
            }
        }
    }
}

@Composable
private fun RowScope.ScrollingColumn(weight: Float, content: @Composable ColumnScope.() -> Unit) {
    val scrollState = rememberScrollState()
    Box(modifier = Modifier.weight(weight).fillMaxHeight()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
fun RiskDot(risk: Risk, size: Dp = 9.dp) {
    Box(modifier = Modifier.size(size).background(riskColor(risk), CircleShape))
}

fun riskColor(risk: Risk): Color = when (risk) {
    Risk.NONE -> RiskColors.none
    Risk.LOW -> RiskColors.low
    Risk.MEDIUM -> RiskColors.medium
    Risk.HIGH -> RiskColors.high
}

@Composable
fun riskLabel(risk: Risk): String = stringResource(
    when (risk) {
        Risk.NONE -> Res.string.risk_none
        Risk.LOW -> Res.string.risk_low
        Risk.MEDIUM -> Res.string.risk_medium
        Risk.HIGH -> Res.string.risk_high
    },
)

/** Resolves a [UiMessage] for display outside the snackbar. */
@Composable
fun textOf(message: UiMessage): String {
    val text by produceState(initialValue = "", message) { value = message.phrase() }
    return text
}
