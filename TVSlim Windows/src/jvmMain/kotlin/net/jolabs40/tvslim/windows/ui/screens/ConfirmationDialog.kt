package net.jolabs40.tvslim.windows.ui.screens

import net.jolabs40.tvslim.windows.ui.components.SecondaryText
import net.jolabs40.tvslim.windows.resources.confirm_reboot_warning
import net.jolabs40.tvslim.windows.resources.confirm_reboot_body
import net.jolabs40.tvslim.windows.resources.confirm_reboot_title
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.configuration.ReinjectionPlan
import net.jolabs40.tvslim.installation.ChosenApk
import net.jolabs40.tvslim.installation.InstallationKind
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.confirm_apply_body
import net.jolabs40.tvslim.windows.resources.confirm_apply_title
import net.jolabs40.tvslim.windows.resources.confirm_cancel
import net.jolabs40.tvslim.windows.resources.confirm_drift_source
import net.jolabs40.tvslim.windows.resources.confirm_drift_title
import net.jolabs40.tvslim.windows.resources.confirm_go
import net.jolabs40.tvslim.windows.resources.confirm_install_downgrade
import net.jolabs40.tvslim.windows.resources.confirm_install_file
import net.jolabs40.tvslim.windows.resources.confirm_install_new
import net.jolabs40.tvslim.windows.resources.confirm_install_note
import net.jolabs40.tvslim.windows.resources.confirm_install_package
import net.jolabs40.tvslim.windows.resources.confirm_install_same
import net.jolabs40.tvslim.windows.resources.confirm_install_title
import net.jolabs40.tvslim.windows.resources.confirm_install_update
import net.jolabs40.tvslim.windows.resources.confirm_reinject_disable
import net.jolabs40.tvslim.windows.resources.confirm_reinject_enable
import net.jolabs40.tvslim.windows.resources.confirm_reinject_home
import net.jolabs40.tvslim.windows.resources.confirm_reinject_home_missing
import net.jolabs40.tvslim.windows.resources.confirm_reinject_ignored
import net.jolabs40.tvslim.windows.resources.confirm_reinject_source
import net.jolabs40.tvslim.windows.resources.confirm_reinject_title
import net.jolabs40.tvslim.windows.resources.confirm_restore_body
import net.jolabs40.tvslim.windows.resources.confirm_restore_title
import net.jolabs40.tvslim.windows.ui.Confirmation
import net.jolabs40.tvslim.windows.ui.megabytes
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Last stop before acting. Side effects known to the catalogue ("the Netflix button stops working") are
 * shown here, when the decision is made. Nothing is sent until confirmed.
 */
@Composable
fun ConfirmationDialog(
    confirmation: Confirmation,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                stringResource(
                    when (confirmation) {
                        is Confirmation.Application -> Res.string.confirm_apply_title
                        is Confirmation.Restore -> Res.string.confirm_restore_title
                        is Confirmation.Reinjection ->
                            if (confirmation.drift) Res.string.confirm_drift_title else Res.string.confirm_reinject_title
                        is Confirmation.Installation -> Res.string.confirm_install_title
                        Confirmation.Reboot -> Res.string.confirm_reboot_title
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (confirmation) {
                    is Confirmation.Application -> {
                        Text(
                            text = stringResource(Res.string.confirm_apply_body, confirmation.entries.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        confirmation.entries.forEach { entry ->
                            Text(
                                text = "• ${entry.name}",
                                modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            entry.sideEffect?.let { effect ->
                                Text(
                                    text = effect,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    is Confirmation.Restore -> {
                        Text(
                            text = stringResource(Res.string.confirm_restore_body, confirmation.packages.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        confirmation.packages.forEach { packageName ->
                            Text(
                                text = "• $packageName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is Confirmation.Reinjection -> Reinjection(confirmation.plan, confirmation.drift)
                    is Confirmation.Installation -> InstallationPreview(confirmation.apk)
                    Confirmation.Reboot -> {
                        Text(text = stringResource(Res.string.confirm_reboot_body), style = MaterialTheme.typography.bodyMedium)
                        SecondaryText(stringResource(Res.string.confirm_reboot_warning), modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.confirm_go)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.confirm_cancel)) }
        },
    )
}

/**
 * What the configuration will change, grouped: what comes back, what goes (side effects included), the
 * home screen, and what this TV lacks.
 */
@Composable
private fun Reinjection(plan: ReinjectionPlan, drift: Boolean) {
    val backup = plan.configuration
    val date = remember(backup.savedAt) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .format(Instant.ofEpochMilli(backup.savedAt).atZone(ZoneId.systemDefault()))
    }
    Text(
        // Drift has no date or source device: its plan comes from this TV's journal.
        text = if (drift) {
            stringResource(Res.string.confirm_drift_source)
        } else {
            stringResource(Res.string.confirm_reinject_source, date, backup.device.name.ifBlank { "—" })
        },
        style = MaterialTheme.typography.bodyMedium,
    )

    if (plan.toEnable.isNotEmpty()) {
        Subheading(stringResource(Res.string.confirm_reinject_enable, plan.toEnable.size))
        plan.toEnable.forEach { Bullet(it.name) }
    }

    if (plan.toDisable.isNotEmpty()) {
        Subheading(stringResource(Res.string.confirm_reinject_disable, plan.toDisable.size))
        plan.toDisable.forEach { entry ->
            Bullet(entry.name)
            entry.sideEffect?.let { effect ->
                Text(text = effect, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    plan.home?.let { home ->
        if (home.possible) {
            Subheading(stringResource(Res.string.confirm_reinject_home, home.name))
        } else {
            Text(
                text = stringResource(Res.string.confirm_reinject_home_missing, home.name),
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (plan.ignores.isNotEmpty()) {
        Text(
            text = stringResource(Res.string.confirm_reinject_ignored, plan.ignores.size),
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The incoming app and what it replaces, so an update is not mistaken for a downgrade, which Android refuses. */
@Composable
private fun InstallationPreview(apk: ChosenApk) {
    val manifest = apk.manifest
    Text(
        text = stringResource(Res.string.confirm_install_file, apk.name, megabytes(apk.size)),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Text(
        text = stringResource(Res.string.confirm_install_package, manifest.packageName, manifest.versionName.ifBlank { "—" }),
        style = MaterialTheme.typography.bodyMedium,
    )

    val inPlace = apk.installed?.let { it.versionName.ifBlank { it.versionCode.toString() } }.orEmpty()
    val (text, color) = when (apk.kind) {
        InstallationKind.NEW ->
            stringResource(Res.string.confirm_install_new) to MaterialTheme.colorScheme.onSurfaceVariant

        InstallationKind.UPDATE ->
            stringResource(Res.string.confirm_install_update, inPlace) to MaterialTheme.colorScheme.onSurfaceVariant

        InstallationKind.REINSTALLATION ->
            stringResource(Res.string.confirm_install_same) to MaterialTheme.colorScheme.onSurfaceVariant

        InstallationKind.DOWNGRADE ->
            stringResource(Res.string.confirm_install_downgrade, inPlace) to MaterialTheme.colorScheme.error
    }
    Text(text = text, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = color)

    Text(
        text = stringResource(Res.string.confirm_install_note),
        modifier = Modifier.padding(top = 12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Subheading(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun Bullet(text: String) {
    Text(text = "• $text", style = MaterialTheme.typography.bodyMedium)
}
