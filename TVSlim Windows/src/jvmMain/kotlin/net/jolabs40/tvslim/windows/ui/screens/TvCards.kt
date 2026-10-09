package net.jolabs40.tvslim.windows.ui.screens

import net.jolabs40.tvslim.windows.resources.device_reboot
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.catalog.RecommendedLauncher
import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.device.DeviceType
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.action_refresh
import net.jolabs40.tvslim.windows.resources.baseline_check_24
import net.jolabs40.tvslim.windows.resources.baseline_open_in_new_24
import net.jolabs40.tvslim.windows.resources.baseline_refresh_24
import net.jolabs40.tvslim.windows.resources.connection_disconnect
import net.jolabs40.tvslim.windows.resources.device_android
import net.jolabs40.tvslim.windows.resources.device_home
import net.jolabs40.tvslim.windows.resources.device_memory
import net.jolabs40.tvslim.windows.resources.device_memory_value
import net.jolabs40.tvslim.windows.resources.device_model
import net.jolabs40.tvslim.windows.resources.device_packages_active
import net.jolabs40.tvslim.windows.resources.device_packages_disabled
import net.jolabs40.tvslim.windows.resources.device_title
import net.jolabs40.tvslim.windows.resources.device_type_box
import net.jolabs40.tvslim.windows.resources.device_type_phone
import net.jolabs40.tvslim.windows.resources.device_type_tablet
import net.jolabs40.tvslim.windows.resources.home_available
import net.jolabs40.tvslim.windows.resources.home_coming_soon
import net.jolabs40.tvslim.windows.resources.home_current_badge
import net.jolabs40.tvslim.windows.resources.home_current_title
import net.jolabs40.tvslim.windows.resources.home_factory_badge
import net.jolabs40.tvslim.windows.resources.home_install
import net.jolabs40.tvslim.windows.resources.home_none
import net.jolabs40.tvslim.windows.resources.home_recommended
import net.jolabs40.tvslim.windows.resources.home_recommended_badge
import net.jolabs40.tvslim.windows.resources.home_title
import net.jolabs40.tvslim.windows.resources.home_use
import net.jolabs40.tvslim.windows.resources.state_disabled
import net.jolabs40.tvslim.windows.ui.AppState
import net.jolabs40.tvslim.windows.ui.components.SectionCard
import net.jolabs40.tvslim.windows.ui.components.ValueRow
import net.jolabs40.tvslim.windows.ui.components.LogoLauncher
import net.jolabs40.tvslim.windows.ui.components.BrandPlate
import net.jolabs40.tvslim.windows.ui.components.SecondaryText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun DeviceCard(
    state: AppState,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onReboot: () -> Unit = {},
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onDisconnect) {
            Text(stringResource(Res.string.connection_disconnect))
        }
        Button(onClick = onRefresh, enabled = !state.loading) {
            Icon(
                painter = painterResource(Res.drawable.baseline_refresh_24),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.action_refresh))
        }
        OutlinedButton(onClick = onReboot, enabled = !state.loading) {
            Text(stringResource(Res.string.device_reboot))
        }
        if (state.loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
    }

    val info = state.info
    val title = when (info.deviceType) {
        DeviceType.TV -> Res.string.device_title
        DeviceType.BOX -> Res.string.device_type_box
        DeviceType.PHONE -> Res.string.device_type_phone
        DeviceType.TABLET -> Res.string.device_type_tablet
    }
    SectionCard(title = stringResource(title), spacing = 6.dp) {
        // Brand first, recognized from what the device reports: see Fabricant.
        info.manufacturer?.let { BrandPlate(manufacturer = it, height = 40.dp, modifier = Modifier.padding(bottom = 6.dp)) }
        ValueRow(stringResource(Res.string.device_model), info.displayName)
        ValueRow(stringResource(Res.string.device_android), state.info.androidVersion)
        ValueRow(
            stringResource(Res.string.device_memory),
            stringResource(Res.string.device_memory_value, state.info.freeMemoryMb, state.info.totalMemoryMb),
        )
        ValueRow(stringResource(Res.string.device_packages_active), state.info.installedPackages.toString())
        ValueRow(stringResource(Res.string.device_packages_disabled), state.info.disabledPackages.toString())
        ValueRow(stringResource(Res.string.device_home), state.info.currentHome.ifBlank { "—" })
    }
}

/**
 * TV home screen.
 *
 * Without a third-party launcher the engine refuses to disable the factory home. The card shows installed
 * launchers by logo (the recommended one first, in green); any launcher not in use can be picked with a button.
 * It suggests a single replacement, the catalogue's, while none of its versions is installed, with a link to its
 * site. It installs nothing itself: until the launcher is on the Play Store the button says so, then it opens
 * the store page on the TV.
 */
@Composable
fun HomeCard(
    state: AppState,
    onInstall: (String) -> Unit,
    onSetHome: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val catalog = state.catalog
    val info = state.info
    val factories = info.factoryHomes
    // A factory home appears only in its own rows, not again among third-party launchers.
    val installed = catalog.recommendedFirst(
        info.thirdPartyLaunchers.filterNot { launcher -> factories.any { it.packageName == launcher.packageName } },
    ) { it.packageName }
    // Nothing to pick while loading or applying packages: the home read may be stale.
    val free = !state.loading && state.progress == null

    SectionCard(title = stringResource(Res.string.home_title), spacing = 10.dp) {
        CurrentHome(catalog = catalog, info = info)
        // Phone or tablet: only the current home is shown. Startlight is a TV launcher, and what the
        // disabled-components query reports as factory homes there (restore, Play Services, device management)
        // are not homes: "Use" would make them the home screen.
        if (!info.deviceType.forCatalog) return@SectionCard

        if (info.thirdPartyLaunchers.isEmpty()) {
            Text(
                text = stringResource(Res.string.home_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (installed.isNotEmpty() || factories.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.home_available),
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
            // Google TV, the Android TV home, the maker's home: listed even when disabled, or the original
            // home would seem gone. Enabled, it can be picked again; disabled, Android would not use it.
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
            RecommendationCard(launcher = launcher, onInstall = onInstall, onOpenLink = onOpenLink)
        }
    }
}

/** Current home, shown large: it is what the TV shows at power-on. */
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
        // Larger than in the list, where it also appears.
        LogoLauncher(id = id, size = 64.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SecondaryText(stringResource(Res.string.home_current_title), small = true)
            Text(
                text = name ?: packageName.ifBlank { "—" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (name != null) SecondaryText(packageName, small = true)
            if (info.factoryHomes.any { it.packageName == packageName }) {
                Badge(stringResource(Res.string.home_factory_badge), MaterialTheme.colorScheme.tertiaryContainer)
            }
        }
    }
}

/** Launcher row ending with "Current" or a button to pick it. */
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
            if (name != null) SecondaryText(packageName, small = true)
        }
        if (factory) Badge(stringResource(Res.string.home_factory_badge), MaterialTheme.colorScheme.tertiaryContainer)
        if (disabled) Badge(stringResource(Res.string.state_disabled), MaterialTheme.colorScheme.errorContainer)
        if (recommended) {
            val (background, contentColor) = recommendedColors()
            Badge(stringResource(Res.string.home_recommended_badge), background, contentColor)
        }
        when {
            current -> Badge(stringResource(Res.string.home_current_badge), MaterialTheme.colorScheme.secondaryContainer)
            onUse != null -> OutlinedButton(onClick = onUse, enabled = free) {
                Text(stringResource(Res.string.home_use))
            }
        }
    }
}

/** Rounded tag: "Recommended", "Factory launcher", "Disabled", "Current". */
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

/** "Recommended" green (background and text), readable on both themes, as on the phone. */
@Composable
private fun recommendedColors(): Pair<Color, Color> =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        Color(0xFF1E5631) to Color(0xFFC8F2D4)
    } else {
        Color(0xFFCDEFD6) to Color(0xFF0F5223)
    }

/** The launcher TV Slim recommends: logo, strengths, and its actions. */
@Composable
private fun RecommendationCard(
    launcher: RecommendedLauncher,
    onInstall: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
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
                    Text(text = launcher.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = stringResource(Res.string.home_recommended),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(text = launcher.description, style = MaterialTheme.typography.bodyMedium)
            launcher.highlights.forEach { point ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        painter = painterResource(Res.drawable.baseline_check_24),
                        contentDescription = null,
                        modifier = Modifier.padding(top = 2.dp).size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(text = point, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // Site first: it is available there well before the store offers it.
                if (launcher.site.isNotBlank()) {
                    Button(onClick = { onOpenLink(launcher.site) }) {
                        Icon(
                            painter = painterResource(Res.drawable.baseline_open_in_new_24),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(launcher.displayedSite)
                    }
                }
                // Not on the Play Store yet: no page to open, and the button says so.
                if (launcher.available) {
                    Button(onClick = { onInstall(launcher.packageName) }) {
                        Text(stringResource(Res.string.home_install))
                    }
                } else {
                    // Greyed out but readable: the default disabled colors nearly vanish on the card.
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        colors = ButtonDefaults.outlinedButtonColors(
                            disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.35f)),
                    ) {
                        Text(stringResource(Res.string.home_coming_soon))
                    }
                }
            }
        }
    }
}
