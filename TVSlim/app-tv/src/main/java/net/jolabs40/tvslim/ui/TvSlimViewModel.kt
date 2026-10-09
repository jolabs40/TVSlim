package net.jolabs40.tvslim.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.catalog.CatalogRepository
import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.catalog.SystemSetting
import net.jolabs40.tvslim.device.DeviceRepository
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.network.LocalNetworkInfo
import net.jolabs40.tvslim.network.ContactPoint
import net.jolabs40.tvslim.system.DriftGuardian
import net.jolabs40.tvslim.system.PreferencesRepository
import net.jolabs40.tvslim.system.SystemSettings
import net.jolabs40.tvslim.system.factoryHomes
import net.jolabs40.tvslim.system.launcherNameOrPackage
import javax.inject.Inject

/** A catalogue entry and its state on this TV. Read-only. */
data class TvPackageRow(
    val entry: PackageEntry,
    val state: PackageState,
)

data class SettingRow(
    val setting: SystemSetting,
    val currentValue: String?,
) {
    val optimized: Boolean get() = currentValue == setting.optimizedValue
}

/** Drift for display, with catalogue names instead of package names. */
data class DisplayedDrift(
    val reenabled: List<String>,
    val lostHome: String?,
)

data class UiState(
    val loading: Boolean = true,
    val directWrite: Boolean = false,
    val guardianActive: Boolean = false,
    val info: DeviceInfo = DeviceInfo.EMPTY,
    val contact: ContactPoint = ContactPoint(),
    val settings: List<SettingRow> = emptyList(),
    val packages: List<TvPackageRow> = emptyList(),
    /** What the last system update undid and is not fixed yet (see `DriftGuardian`). */
    val drift: DisplayedDrift? = null,
    val message: String? = null,
) {
    /** Catalogue entries actually installed on this TV. */
    val presentPackages: List<TvPackageRow>
        get() = packages.filter { it.state != PackageState.ABSENT }

    val disabledPackages: Int get() = packages.count { it.state == PackageState.DISABLED }
}

/**
 * State shared by all screens. Debloating is driven from the phone, so this app only shows the TV's state
 * and keeps the system settings that must survive reboots.
 */
@HiltViewModel
class TvSlimViewModel @Inject constructor(
    private val catalogRepo: CatalogRepository,
    private val device: DeviceRepository,
    private val systemSettings: SystemSettings,
    private val networkInfo: LocalNetworkInfo,
    private val preferences: PreferencesRepository,
    private val driftGuardian: DriftGuardian,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.guardianActive.collect { active ->
                _state.update { it.copy(guardianActive = active) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val catalog = catalogRepo.catalog()
            val homePackages = catalog.entries
                .filter { it.requiresThirdPartyLauncher }
                .map { it.packageName }
                .toSet()
            val info = device.info(homePackages)
            val settings = withContext(Dispatchers.IO) {
                catalog.settings.map { SettingRow(it, systemSettings.read(it)) }
            }
            val packages = withContext(Dispatchers.IO) {
                catalog.entries.map { TvPackageRow(it, device.state(it.packageName)) }
            }
            val contact = withContext(Dispatchers.IO) { networkInfo.contactPoint() }
            val drift = remainingDrift(catalog, packages, info.currentHome)
            _state.update {
                it.copy(
                    loading = false,
                    info = info,
                    contact = contact,
                    settings = settings,
                    packages = packages,
                    drift = drift,
                    directWrite = systemSettings.canWriteDirectly(),
                )
            }
        }
    }

    /**
     * What is left of the last drift after rereading the TV. Anything the phone or PC fixed since is
     * dropped, and a fully fixed report is cleared.
     */
    private suspend fun remainingDrift(
        catalog: Catalog,
        packages: List<TvPackageRow>,
        home: String,
    ): DisplayedDrift? {
        val stored = preferences.drift.first() ?: return null
        val active = packages.filter { it.state == PackageState.ACTIVE }.map { it.entry.packageName }.toSet()
        val remaining = stored.remaining(active, home, catalog.factoryHomes())
        if (remaining != stored) preferences.rememberDrift(remaining)
        if (remaining.empty) return null
        val names = packages.associate { it.entry.packageName to it.entry.name }
        return DisplayedDrift(
            reenabled = remaining.reenabled.map { names[it] ?: it },
            lostHome = remaining.lostHome?.let(catalog::launcherNameOrPackage),
        )
    }

    fun toggleSetting(line: SettingRow) {
        viewModelScope.launch {
            val target = if (line.optimized) {
                line.setting.defaultValue
            } else {
                line.setting.optimizedValue
            }
            val error = withContext(Dispatchers.IO) { systemSettings.write(line.setting, target) }
            show(error ?: "${line.setting.name} : $target")
            refresh()
        }
    }

    fun setGuardianEnabled(active: Boolean) {
        viewModelScope.launch {
            preferences.setGuardianEnabled(active)
            // First snapshot right away, or the first system update would go unnoticed.
            if (active) withContext(Dispatchers.IO) { runCatching { driftGuardian.check() } }
            if (active && !systemSettings.canWriteDirectly()) {
                show(
                    "Gardien activé, mais inopérant tant que l'autorisation d'écriture " +
                        "des réglages n'a pas été accordée par ADB.",
                )
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun show(text: String) = _state.update { it.copy(message = text) }
}
