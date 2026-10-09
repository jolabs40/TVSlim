package net.jolabs40.tvslim.windows.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.catalog.DEVICE_CATEGORY
import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.origin
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.baseline_info_24
import net.jolabs40.tvslim.windows.resources.baseline_warning_24
import net.jolabs40.tvslim.windows.resources.packages_detail_category
import net.jolabs40.tvslim.windows.resources.packages_detail_hint
import net.jolabs40.tvslim.windows.resources.packages_detail_origin
import net.jolabs40.tvslim.windows.resources.packages_detail_risk
import net.jolabs40.tvslim.windows.resources.packages_detail_selected
import net.jolabs40.tvslim.windows.resources.packages_detail_size
import net.jolabs40.tvslim.windows.resources.packages_detail_state
import net.jolabs40.tvslim.windows.resources.packages_device_app_detail
import net.jolabs40.tvslim.windows.resources.packages_reactivate
import net.jolabs40.tvslim.windows.resources.packages_requires_launcher
import net.jolabs40.tvslim.windows.resources.packages_untested_detail
import net.jolabs40.tvslim.windows.resources.side_effect_prefix
import net.jolabs40.tvslim.windows.resources.size_mb
import net.jolabs40.tvslim.windows.resources.state_disabled
import net.jolabs40.tvslim.windows.resources.state_enabled
import net.jolabs40.tvslim.windows.ui.PackageRow
import net.jolabs40.tvslim.windows.ui.components.OriginIcon
import net.jolabs40.tvslim.windows.ui.components.ValueRow
import net.jolabs40.tvslim.windows.ui.components.RiskDot
import net.jolabs40.tvslim.windows.ui.components.SecondaryText
import net.jolabs40.tvslim.windows.ui.components.originLabel
import net.jolabs40.tvslim.windows.ui.components.riskLabel
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Everything the catalogue knows about a package, with the matching action. */
@Composable
fun PackageDetail(
    line: PackageRow?,
    catalog: Catalog,
    onToggle: (String) -> Unit,
    onEnable: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.padding(24.dp)) {
        if (line == null) {
            SecondaryText(
                stringResource(Res.string.packages_detail_hint),
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        val entry = line.entry

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = entry.name, style = MaterialTheme.typography.headlineSmall)
            SelectionContainer {
                Text(
                    text = entry.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (line.state) {
                PackageState.ACTIVE -> Row(
                    modifier = Modifier.clickable { onToggle(entry.packageName) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = line.selected, onCheckedChange = { onToggle(entry.packageName) })
                    Text(stringResource(Res.string.packages_detail_selected))
                }

                PackageState.DISABLED -> Button(onClick = { onEnable(entry.packageName) }) {
                    Text(stringResource(Res.string.packages_reactivate))
                }

                PackageState.ABSENT -> Unit
            }

            HorizontalDivider()

            ValueRow(
                stringResource(Res.string.packages_detail_state),
                stringResource(if (line.state == PackageState.DISABLED) Res.string.state_disabled else Res.string.state_enabled),
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SecondaryText(stringResource(Res.string.packages_detail_risk), modifier = Modifier.weight(1f))
                RiskDot(entry.risk)
                Spacer(Modifier.width(8.dp))
                Text(text = riskLabel(entry.risk), style = MaterialTheme.typography.bodyMedium)
            }
            ValueRow(stringResource(Res.string.packages_detail_category), catalog.categoryName(entry.category))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SecondaryText(stringResource(Res.string.packages_detail_origin), modifier = Modifier.weight(1f))
                OriginIcon(entry.origin)
                Spacer(Modifier.width(8.dp))
                Text(text = originLabel(entry.origin), style = MaterialTheme.typography.bodyMedium)
            }
            entry.sizeMb?.let { size ->
                ValueRow(stringResource(Res.string.packages_detail_size), stringResource(Res.string.size_mb, size))
            }

            HorizontalDivider()

            Text(text = entry.description, style = MaterialTheme.typography.bodyLarge)

            entry.sideEffect?.let { effect ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        painter = painterResource(Res.drawable.baseline_warning_24),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(Res.string.side_effect_prefix, effect),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (!entry.tested) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        painter = painterResource(Res.drawable.baseline_info_24),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        // Phone apps have no catalogue description: see avecApplicationsDuMenu.
                        text = stringResource(
                            if (entry.category == DEVICE_CATEGORY) {
                                Res.string.packages_device_app_detail
                            } else {
                                Res.string.packages_untested_detail
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            if (entry.requiresThirdPartyLauncher) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        painter = painterResource(Res.drawable.baseline_info_24),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SecondaryText(stringResource(Res.string.packages_requires_launcher))
                }
            }
        }
    }
}
