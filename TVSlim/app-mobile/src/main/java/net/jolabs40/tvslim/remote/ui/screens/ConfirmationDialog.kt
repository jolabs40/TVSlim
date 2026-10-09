package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.configuration.ReinjectionPlan
import net.jolabs40.tvslim.installation.ChosenApk
import net.jolabs40.tvslim.installation.InstallationKind
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.Confirmation
import net.jolabs40.tvslim.remote.ui.megabytes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Last stop before acting. Shows the catalogue's side effects (the Netflix key stops working, YouTube casting
 * breaks...) at the moment of decision; nothing is sent until confirmed.
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
                        is Confirmation.Application -> R.string.confirm_apply_title
                        is Confirmation.Restore -> R.string.confirm_restore_title
                        is Confirmation.Reinjection ->
                            if (confirmation.drift) R.string.confirm_drift_title else R.string.confirm_reinject_title
                        is Confirmation.Installation -> R.string.confirm_install_title
                        Confirmation.Reboot -> R.string.confirm_reboot_title
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (confirmation) {
                    is Confirmation.Application -> {
                        Text(
                            text = stringResource(
                                R.string.confirm_apply_body,
                                confirmation.entries.size,
                            ),
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
                            text = stringResource(
                                R.string.confirm_restore_body,
                                confirmation.packages.size,
                            ),
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
                        Text(text = stringResource(R.string.confirm_reboot_body), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.confirm_reboot_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.confirm_go))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.confirm_cancel))
            }
        },
    )
}

@Composable
private fun Reinjection(plan: ReinjectionPlan, drift: Boolean) {
    val backup = plan.configuration
    val date = remember(backup.savedAt) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .format(Instant.ofEpochMilli(backup.savedAt).atZone(ZoneId.systemDefault()))
    }
    Text(
        // A drift plan has no date or source device: it comes from this TV's log.
        text = if (drift) {
            stringResource(R.string.confirm_drift_source)
        } else {
            stringResource(R.string.confirm_reinject_source, date, backup.device.name.ifBlank { "—" })
        },
        style = MaterialTheme.typography.bodyMedium,
    )

    if (plan.toEnable.isNotEmpty()) {
        Subheading(stringResource(R.string.confirm_reinject_enable, plan.toEnable.size))
        plan.toEnable.forEach { Bullet(it.name) }
    }

    if (plan.toDisable.isNotEmpty()) {
        Subheading(stringResource(R.string.confirm_reinject_disable, plan.toDisable.size))
        plan.toDisable.forEach { entry ->
            Bullet(entry.name)
            entry.sideEffect?.let { effect ->
                Text(text = effect, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    plan.home?.let { home ->
        if (home.possible) {
            Subheading(stringResource(R.string.confirm_reinject_home, home.name))
        } else {
            Text(
                text = stringResource(R.string.confirm_reinject_home_missing, home.name),
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (plan.ignores.isNotEmpty()) {
        Text(
            text = stringResource(R.string.confirm_reinject_ignored, plan.ignores.size),
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shows what the APK replaces, so that a downgrade, which Android will refuse, is not mistaken for an update. */
@Composable
private fun InstallationPreview(apk: ChosenApk) {
    val manifest = apk.manifest
    Text(
        text = stringResource(R.string.confirm_install_file, apk.name, megabytes(apk.size)),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Text(
        text = stringResource(R.string.confirm_install_package, manifest.packageName, manifest.versionName.ifBlank { "—" }),
        style = MaterialTheme.typography.bodyMedium,
    )

    val inPlace = apk.installed?.let { it.versionName.ifBlank { it.versionCode.toString() } }.orEmpty()
    val (text, color) = when (apk.kind) {
        InstallationKind.NEW ->
            stringResource(R.string.confirm_install_new) to MaterialTheme.colorScheme.onSurfaceVariant

        InstallationKind.UPDATE ->
            stringResource(R.string.confirm_install_update, inPlace) to MaterialTheme.colorScheme.onSurfaceVariant

        InstallationKind.REINSTALLATION ->
            stringResource(R.string.confirm_install_same) to MaterialTheme.colorScheme.onSurfaceVariant

        InstallationKind.DOWNGRADE ->
            stringResource(R.string.confirm_install_downgrade, inPlace) to MaterialTheme.colorScheme.error
    }
    Text(text = text, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = color)

    Text(
        text = stringResource(R.string.confirm_install_note),
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
