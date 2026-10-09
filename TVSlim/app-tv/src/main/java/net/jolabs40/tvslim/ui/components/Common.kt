package net.jolabs40.tvslim.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import net.jolabs40.tvslim.catalog.Risk
import net.jolabs40.tvslim.ui.theme.RiskColors

/** Screen title with an optional explanation line. */
@Composable
fun Header(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier = modifier.padding(bottom = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Information block: a plain background, never a D-pad target. */
@Composable
fun InfoBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        content = content,
    )
}

/** Label and value row of a measurement block. */
@Composable
fun MeasurementRow(label: String, rawValue: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
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

/** Colored dot showing the risk of disabling a package. */
@Composable
fun RiskDot(risk: Risk, modifier: Modifier = Modifier) {
    val color = when (risk) {
        Risk.NONE -> RiskColors.none
        Risk.LOW -> RiskColors.low
        Risk.MEDIUM -> RiskColors.medium
        Risk.HIGH -> RiskColors.high
    }
    Box(modifier = modifier.size(10.dp).background(color, CircleShape))
}

/**
 * Focusable list row; the whole row is the D-pad target.
 *
 * Compose for TV's default 1.1x focus scale makes neighbouring rows jump on every move, which is tiring
 * on a long list. Scale is cut to 1.01x; the focused row's light background is enough to mark it.
 */
@Composable
fun FocusableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Box(modifier = Modifier.padding(14.dp), contentAlignment = Alignment.CenterStart) {
            content()
        }
    }
}

/** Position counter for long lists ("12 / 56"). With a remote there is no other cue for position. */
@Composable
fun ListCounter(
    state: LazyListState,
    total: Int,
    modifier: Modifier = Modifier,
    currentIndex: Int? = null,
) {
    if (total <= 0) return
    // The focused item wins over the first visible one. derivedStateOf because `firstVisibleItemIndex`
    // changes on every scroll frame while the shown number only changes per row; without it the counter
    // recomposes 60 times a second.
    val current by remember(state, total, currentIndex) {
        derivedStateOf {
            val position = currentIndex?.takeIf { it >= 0 } ?: state.firstVisibleItemIndex
            (position + 1).coerceAtMost(total)
        }
    }
    Text(
        text = "$current / $total",
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Decorative scrollbar following the list state. Not interactive: with a remote it only shows position. */
@Composable
fun ScrollBar(state: LazyListState, modifier: Modifier = Modifier) {
    // `layoutInfo` changes on every scroll frame, these three numbers do not. Deriving them avoids
    // redrawing the bar for an unchanged position.
    val extent by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            ListExtent(info.totalItemsCount, info.visibleItemsInfo.size, state.firstVisibleItemIndex)
        }
    }
    val (total, visible, first) = extent
    if (total == 0 || visible == 0 || total <= visible) return

    val proportion = (visible.toFloat() / total).coerceIn(FRACTION_MIN, 1f)
    val progress = first.toFloat() / (total - visible).coerceAtLeast(1)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                RoundedCornerShape(2.dp),
            ),
    ) {
        val cursorHeight = maxHeight * proportion
        Box(
            modifier = Modifier
                .offset(y = (maxHeight - cursorHeight) * progress.coerceIn(0f, 1f))
                .height(cursorHeight)
                .fillMaxWidth()
                // Kept faint: a position cue, not a control.
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(2.dp),
                ),
        )
    }
}

/** Feedback banner shown after each action. */
@Composable
fun MessageStrip(message: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClose,
        modifier = modifier.fillMaxWidth(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Text(
                text = message,
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** The list metrics the scrollbar needs. */
private data class ListExtent(val total: Int, val visible: Int, val first: Int)

/** Minimum thumb size, so it stays visible on very long lists. */
private const val FRACTION_MIN = 0.08f

