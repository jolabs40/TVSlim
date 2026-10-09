package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.catalog.RecommendedLauncher
import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.RemoteState

/**
 * The TV's home screen card.
 *
 * The engine will not disable the factory home screen without a third-party launcher, so the card lists the
 * installed launchers, recommended first, each selectable. It suggests only the catalogue's launcher, while no variant
 * of it is installed. The phone installs nothing: the button opens the listing in the TV's own store, where the
 * install is confirmed with the remote.
 */
@Composable
fun HomeCard(state: RemoteState, onInstall: (String) -> Unit, onSetHome: (String) -> Unit) {
    val catalog = state.catalog
    val info = state.info
    val factories = info.factoryHomes
    // Factory home screens have their own rows, so they are not repeated among third-party launchers.
    val installed = catalog.recommendedFirst(
        info.thirdPartyLaunchers.filterNot { launcher -> factories.any { it.packageName == launcher.packageName } },
    ) { it.packageName }
    // Locked while loading or applying packages: the current home screen may be stale.
    val free = !state.loading && state.progress == null

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            CurrentHome(catalog = catalog, info = info)
            // Phones and tablets show only the current home screen. Startlight is a TV launcher, and the disabled HOME
            // activities found there (restore, Play Services, device management) are not real home screens.
            if (!info.deviceType.forCatalog) return@Column

            if (info.thirdPartyLaunchers.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (installed.isNotEmpty() || factories.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.home_available),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                installed.forEach { launcher ->
                    val current = launcher.packageName == info.currentHome
                    LauncherRow(
                        id = catalog.launcherId(launcher.packageName),
                        name = catalog.launcherName(launcher.packageName),
                        packageName = launcher.packageName,
                        recommended = catalog.recommendedLauncher(launcher.packageName) != null,
                        current = current,
                        onUse = { onSetHome(launcher.component) }
                            .takeIf { !current && launcher.component.isNotBlank() },
                        free = free,
                    )
                }
                // Listed even when disabled, or the original home screen would seem to be gone. Only an
                // enabled one is selectable: Android would not serve a disabled one.
                factories.forEach { factory ->
                    val current = factory.packageName == info.currentHome
                    LauncherRow(
                        id = null,
                        name = catalog.entries.firstOrNull { it.packageName == factory.packageName }?.name,
                        packageName = factory.packageName,
                        recommended = false,
                        factory = true,
                        disabled = !factory.active,
                        current = current,
                        onUse = { onSetHome(factory.component) }
                            .takeIf { !current && factory.active && factory.component.isNotBlank() },
                        free = free,
                    )
                }
            }

            catalog.launchersToOffer(installed.map { it.packageName }).forEach { launcher ->
                RecommendationCard(launcher = launcher, onInstall = onInstall)
            }
        }
    }
}

@Composable
private fun CurrentHome(catalog: Catalog, info: DeviceInfo) {
    val packageName = info.currentHome
    val name = packageName.takeIf { it.isNotBlank() }?.let { current ->
        catalog.launcherName(current) ?: catalog.entries.firstOrNull { it.packageName == current }?.name
    }
    val id = packageName.takeIf { it.isNotBlank() }?.let(catalog::launcherId)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LogoLauncher(id = id, size = 64.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.home_current_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = name ?: packageName.ifBlank { "—" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (name != null) {
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (info.factoryHomes.any { it.packageName == packageName }) {
                Badge(stringResource(R.string.home_factory_badge), MaterialTheme.colorScheme.tertiaryContainer)
            }
        }
    }
}

@Composable
private fun LauncherRow(
    id: String?,
    name: String?,
    packageName: String,
    recommended: Boolean,
    factory: Boolean = false,
    disabled: Boolean = false,
    current: Boolean = false,
    onUse: (() -> Unit)? = null,
    free: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LogoLauncher(id = id, size = 40.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name ?: packageName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            )
            if (name != null) {
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Under the name: at the end of the row, two badges would squeeze the text on a phone.
            if (recommended || factory || disabled) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (recommended) {
                        val (background, contentColor) = recommendedColors()
                        Badge(stringResource(R.string.home_recommended_badge), background, contentColor)
                    }
                    if (factory) {
                        Badge(stringResource(R.string.home_factory_badge), MaterialTheme.colorScheme.tertiaryContainer)
                    }
                    if (disabled) {
                        Badge(stringResource(R.string.home_disabled_badge), MaterialTheme.colorScheme.errorContainer)
                    }
                }
            }
        }
        when {
            current -> Badge(stringResource(R.string.home_current_badge), MaterialTheme.colorScheme.secondaryContainer)
            onUse != null -> OutlinedButton(onClick = onUse, enabled = free) {
                Text(stringResource(R.string.home_use))
            }
        }
    }
}

@Composable
private fun Badge(text: String, background: Color, contentColor: Color = Color.Unspecified) {
    Surface(color = background, shape = RoundedCornerShape(50)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

/** Fixed green (background, text) for the Recommended badge, readable in both themes whatever the color scheme. */
@Composable
private fun recommendedColors(): Pair<Color, Color> =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        Color(0xFF1E5631) to Color(0xFFC8F2D4)
    } else {
        Color(0xFFCDEFD6) to Color(0xFF0F5223)
    }

@Composable
private fun RecommendationCard(launcher: RecommendedLauncher, onInstall: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LogoLauncher(id = launcher.id, size = 56.dp)
                Column {
                    Text(
                        text = launcher.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.home_recommended),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(text = launcher.description, style = MaterialTheme.typography.bodyMedium)
            launcher.highlights.forEach { point ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.padding(top = 2.dp).size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(text = point, style = MaterialTheme.typography.bodyMedium)
                }
            }
            // Website first: the launcher is there long before it reaches the store.
            if (launcher.site.isNotBlank()) {
                val links = LocalUriHandler.current
                Button(onClick = { runCatching { links.openUri(launcher.site) } }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(launcher.displayedSite)
                }
            }
            if (launcher.available) {
                Button(onClick = { onInstall(launcher.packageName) }) {
                    Text(stringResource(R.string.home_install))
                }
            } else {
                // Not on the Play Store yet. The default disabled colors nearly vanish on the card background.
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    colors = ButtonDefaults.outlinedButtonColors(
                        disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.35f)),
                ) {
                    Text(stringResource(R.string.home_coming_soon))
                }
            }
        }
    }
}
