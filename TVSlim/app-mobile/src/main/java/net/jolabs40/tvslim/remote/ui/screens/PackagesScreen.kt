package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.catalog.Profile
import net.jolabs40.tvslim.catalog.Risk
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.CatalogSuggestion
import net.jolabs40.tvslim.device.origin
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.RemoteState
import net.jolabs40.tvslim.remote.ui.PackageFilter
import net.jolabs40.tvslim.remote.ui.PackageRow
import net.jolabs40.tvslim.remote.ui.theme.RiskColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagesScreen(
    state: RemoteState,
    onToggle: (String) -> Unit,
    onProfileSelected: (Profile) -> Unit,
    onUncheckAll: () -> Unit,
    onApply: () -> Unit,
    onEnable: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    onFilterSelected: (PackageFilter) -> Unit,
    onSave: () -> Unit,
    onReinject: () -> Unit,
    onExportUnknowns: () -> Unit,
    onSuggestUnknowns: () -> Unit,
) {
    if (!state.connected) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                text = stringResource(R.string.packages_not_connected),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val shown = state.shown

    Column(modifier = Modifier.fillMaxWidth()) {
        state.progress?.let { progress ->
            LinearProgressIndicator(
                progress = {
                    if (progress.total == 0) 0f else progress.done.toFloat() / progress.total
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(
                    R.string.packages_progress,
                    progress.done,
                    progress.total,
                ),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        OutlinedTextField(
            value = state.search,
            onValueChange = onSearchChange,
            label = { Text(stringResource(R.string.packages_search)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            PackageFilter.entries.forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = state.filter == choice,
                    onClick = { onFilterSelected(choice) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = PackageFilter.entries.size),
                    // No checkmark: three labels with their counts barely fit on a phone.
                    icon = {},
                ) {
                    Text(
                        text = when (choice) {
                            PackageFilter.ALL -> stringResource(R.string.filter_all)
                            PackageFilter.ACTIVE -> stringResource(R.string.filter_enabled, state.activeCount)
                            PackageFilter.DISABLED -> stringResource(R.string.filter_disabled, state.disabledCount)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Phones and tablets: the catalogue is not written for them, so profiles are disabled.
        val forCatalog = state.info.deviceType.forCatalog
        if (!forCatalog) {
            Text(
                text = stringResource(R.string.packages_not_tv),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        LazyRow(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.catalog.profiles) { profile ->
                AssistChip(onClick = { onProfileSelected(profile) }, label = { Text(profile.name) }, enabled = forCatalog)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onApply, enabled = !state.workInProgress) {
                Text(stringResource(R.string.packages_apply, state.selection.size))
            }
            TextButton(onClick = onUncheckAll) {
                Text(stringResource(R.string.packages_clear))
            }
        }

        // Save and restore sit next to the profiles: a saved configuration (launcher and packages) is a custom profile.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onSave,
                enabled = !state.workInProgress,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.config_save), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = onReinject,
                enabled = !state.workInProgress,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.config_reinject), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

        val unknowns = state.shownUnknowns
        if (shown.isEmpty() && unknowns.isEmpty()) {
            Text(
                text = stringResource(R.string.packages_none_matching),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(shown, key = { it.entry.packageName }) { line ->
                PackageView(
                    line = line,
                    onClick = {
                        if (line.state == PackageState.DISABLED) {
                            onEnable(line.entry.packageName)
                        } else {
                            onToggle(line.entry.packageName)
                        }
                    },
                )
            }
            // Packages unknown to the catalogue: shown, never offered for disabling.
            if (state.unknowns.isNotEmpty()) {
                unknownsSection(
                    shown = unknowns,
                    total = state.unknowns.size,
                    onExport = onExportUnknowns,
                    onSuggest = onSuggestUnknowns.takeIf { CatalogSuggestion.shouldOffer(state.unknowns) },
                )
            }
        }
    }
}

@Composable
private fun PackageView(line: PackageRow, onClick: () -> Unit) {
    val disabled = line.state == PackageState.DISABLED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (disabled) {
            TextButton(onClick = onClick) { Text(stringResource(R.string.packages_reactivate)) }
        } else {
            Checkbox(checked = line.selected, onCheckedChange = { onClick() })
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OriginIcon(line.entry.origin, modifier = Modifier.padding(end = 8.dp))
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(color(line.entry.risk), CircleShape),
                )
                Text(
                    text = line.entry.name,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                line.entry.sizeMb?.let { size ->
                    Text(
                        text = stringResource(R.string.size_mb, size),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = line.entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Described from a submitted inventory: checked by hand only, never by a profile.
            if (!line.entry.tested) {
                Text(
                    text = stringResource(R.string.packages_untested),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            line.entry.sideEffect?.let { effect ->
                Text(
                    text = stringResource(R.string.side_effect_prefix, effect),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun color(risk: Risk) = when (risk) {
    Risk.NONE -> RiskColors.none
    Risk.LOW -> RiskColors.low
    Risk.MEDIUM -> RiskColors.medium
    Risk.HIGH -> RiskColors.high
}
