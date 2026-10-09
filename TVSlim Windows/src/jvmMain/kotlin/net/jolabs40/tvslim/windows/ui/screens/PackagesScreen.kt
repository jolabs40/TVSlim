package net.jolabs40.tvslim.windows.ui.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.catalog.Profile
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.CatalogSuggestion
import net.jolabs40.tvslim.device.origin
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.baseline_arrow_drop_down_24
import net.jolabs40.tvslim.windows.resources.baseline_search_24
import net.jolabs40.tvslim.windows.resources.baseline_warning_24
import net.jolabs40.tvslim.windows.resources.config_reinject
import net.jolabs40.tvslim.windows.resources.config_save
import net.jolabs40.tvslim.windows.resources.filter_all
import net.jolabs40.tvslim.windows.resources.filter_disabled
import net.jolabs40.tvslim.windows.resources.filter_enabled
import net.jolabs40.tvslim.windows.resources.packages_apply
import net.jolabs40.tvslim.windows.resources.packages_clear
import net.jolabs40.tvslim.windows.resources.packages_none_matching
import net.jolabs40.tvslim.windows.resources.packages_not_connected
import net.jolabs40.tvslim.windows.resources.packages_not_tv
import net.jolabs40.tvslim.windows.resources.packages_profiles
import net.jolabs40.tvslim.windows.resources.packages_progress
import net.jolabs40.tvslim.windows.resources.packages_reactivate
import net.jolabs40.tvslim.windows.resources.packages_search
import net.jolabs40.tvslim.windows.resources.packages_untested
import net.jolabs40.tvslim.windows.resources.side_effect_prefix
import net.jolabs40.tvslim.windows.resources.size_mb
import net.jolabs40.tvslim.windows.ui.AppState
import net.jolabs40.tvslim.windows.ui.PackageFilter
import net.jolabs40.tvslim.windows.ui.PackageRow
import net.jolabs40.tvslim.windows.ui.components.EmptyScreen
import net.jolabs40.tvslim.windows.ui.components.OriginIcon
import net.jolabs40.tvslim.windows.ui.components.RiskDot
import net.jolabs40.tvslim.windows.ui.components.SecondaryText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Packages tab: the catalogue filtered to what the TV actually has, profiles, batch apply. Same information
 * as on the phone, plus a detail pane where a package can be read in full without ticking it by mistake.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PackagesScreen(
    state: AppState,
    onToggle: (String) -> Unit,
    onShowDetails: (String) -> Unit,
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
        EmptyScreen(stringResource(Res.string.packages_not_connected))
        return
    }

    val shown = state.shown

    Column(modifier = Modifier.fillMaxSize()) {
        state.progress?.let { progress ->
            LinearProgressIndicator(
                progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryText(
                stringResource(Res.string.packages_progress, progress.done, progress.total),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                small = true,
            )
        }

        // Phone or tablet: the catalogue is not written for them and no profile applies.
        val forCatalog = state.info.deviceType.forCatalog
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!forCatalog) OffTvStrip()
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.search,
                    onValueChange = onSearchChange,
                    placeholder = { Text(stringResource(Res.string.packages_search)) },
                    leadingIcon = { Icon(painterResource(Res.drawable.baseline_search_24), contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).widthIn(max = 480.dp),
                )
                // Save and reapply the TV configuration (launcher and packages): a save is a self-made
                // profile. The search field shrinks to make room here; the filter row has no room left.
                OutlinedButton(onClick = onSave, enabled = !state.workInProgress) {
                    Text(stringResource(Res.string.config_save))
                }
                OutlinedButton(onClick = onReinject, enabled = !state.workInProgress) {
                    Text(stringResource(Res.string.config_reinject))
                }
                TextButton(onClick = onUncheckAll) { Text(stringResource(Res.string.packages_clear)) }
                Button(onClick = onApply, enabled = !state.workInProgress) {
                    Text(stringResource(Res.string.packages_apply, state.selection.size))
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateFilter(
                    filter = state.filter,
                    active = state.activeCount,
                    disabled = state.disabledCount,
                    onFilterSelected = onFilterSelected,
                )
                Spacer(Modifier.weight(1f))
                // A dropdown rather than a row of buttons: five long profile names wrapped onto two
                // lines, and the dropdown has room to describe what each one selects.
                ProfileList(
                    profiles = state.catalog.profiles,
                    selectionEmpty = state.selection.isEmpty(),
                    active = forCatalog,
                    onProfileSelected = onProfileSelected,
                )
            }
        }

        HorizontalDivider()

        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1.35f).fillMaxHeight()) {
                val unknowns = state.shownUnknowns
                if (shown.isEmpty() && unknowns.isEmpty()) {
                    SecondaryText(
                        stringResource(Res.string.packages_none_matching),
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    val list = rememberLazyListState()
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                        items(shown, key = { it.entry.packageName }) { line ->
                            PackageView(
                                line = line,
                                chosen = line.entry.packageName == state.detailedPackage,
                                onShowDetails = { onShowDetails(line.entry.packageName) },
                                onToggle = { onToggle(line.entry.packageName) },
                                onEnable = { onEnable(line.entry.packageName) },
                            )
                        }
                        // Then the packages the catalogue does not know: shown, never offered for disabling.
                        if (state.unknowns.isNotEmpty()) {
                            unknownsSection(
                                shown = unknowns,
                                total = state.unknowns.size,
                                onExport = onExportUnknowns,
                                onSuggest = onSuggestUnknowns.takeIf { CatalogSuggestion.shouldOffer(state.unknowns) },
                            )
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(list),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
            VerticalDivider()
            PackageDetail(
                line = state.detailedRow,
                catalog = state.catalog,
                onToggle = onToggle,
                onEnable = onEnable,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/** All, enabled or disabled: one three-way toggle rather than three buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StateFilter(filter: PackageFilter, active: Int, disabled: Int, onFilterSelected: (PackageFilter) -> Unit) {
    // Fixed width and no check mark: otherwise the row shrinks to its labels' minimum width and a label
    // like "Disabled (55)" loses its count.
    SingleChoiceSegmentedButtonRow(modifier = Modifier.width(456.dp)) {
        PackageFilter.entries.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = filter == choice,
                onClick = { onFilterSelected(choice) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = PackageFilter.entries.size),
                icon = {},
            ) {
                Text(
                    text = when (choice) {
                        PackageFilter.ALL -> stringResource(Res.string.filter_all)
                        PackageFilter.ACTIVE -> stringResource(Res.string.filter_enabled, active)
                        PackageFilter.DISABLED -> stringResource(Res.string.filter_disabled, disabled)
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** Shown for a phone or tablet: what the catalogue will not do for it, and what remains possible. */
@Composable
private fun OffTvStrip() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(Res.drawable.baseline_warning_24), contentDescription = null)
            Text(stringResource(Res.string.packages_not_tv), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Profile dropdown. Picking one selects everything it covers without unselecting anything; the description
 * under each name says what applying it costs. The field shows the last applied profile until the selection
 * is cleared.
 */
@Composable
private fun ProfileList(profiles: List<Profile>, selectionEmpty: Boolean, active: Boolean, onProfileSelected: (Profile) -> Unit) {
    var opened by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<Profile?>(null) }

    Box(modifier = Modifier.width(300.dp)) {
        OutlinedTextField(
            value = if (selectionEmpty) "" else last?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(Res.string.packages_profiles)) },
            enabled = active,
            trailingIcon = {
                Icon(painter = painterResource(Res.drawable.baseline_arrow_drop_down_24), contentDescription = null)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // A read-only text field swallows clicks, so a transparent overlay receives them.
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(top = 8.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable(enabled = active) { opened = true },
        )
        DropdownMenu(
            expanded = opened,
            onDismissRequest = { opened = false },
            modifier = Modifier.width(460.dp),
        ) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = {
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = profile.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        opened = false
                        last = profile
                        onProfileSelected(profile)
                    },
                )
            }
        }
    }
}

/**
 * A catalogue row. A click opens it in the detail pane; only the checkbox selects. An already disabled
 * package cannot be selected: it is re-enabled with an explicit button.
 */
@Composable
private fun PackageView(
    line: PackageRow,
    chosen: Boolean,
    onShowDetails: () -> Unit,
    onToggle: () -> Unit,
    onEnable: () -> Unit,
) {
    val background = if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onShowDetails)
            .padding(end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(120.dp), contentAlignment = Alignment.Center) {
            if (line.state == PackageState.DISABLED) {
                TextButton(onClick = onEnable) { Text(stringResource(Res.string.packages_reactivate)) }
            } else {
                Checkbox(checked = line.selected, onCheckedChange = { onToggle() })
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OriginIcon(line.entry.origin, modifier = Modifier.padding(end = 8.dp))
                RiskDot(line.entry.risk)
                Text(
                    text = line.entry.name,
                    modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Described from a submitted inventory: no profile selects it, and the detail pane says why.
                if (!line.entry.tested) {
                    Text(
                        text = stringResource(Res.string.packages_untested),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                line.entry.sizeMb?.let { size ->
                    SecondaryText(
                        stringResource(Res.string.size_mb, size),
                        modifier = Modifier.padding(start = 8.dp),
                        small = true,
                    )
                }
            }
            Text(
                text = line.entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            line.entry.sideEffect?.let { effect ->
                Text(
                    text = stringResource(Res.string.side_effect_prefix, effect),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
