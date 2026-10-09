package net.jolabs40.tvslim.windows.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.windows.AppInfo
import net.jolabs40.tvslim.windows.update.UpdateState
import net.jolabs40.tvslim.windows.update.DistributionMode
import net.jolabs40.tvslim.windows.update.UpdatePhase
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.about_close
import net.jolabs40.tvslim.windows.resources.about_contact
import net.jolabs40.tvslim.windows.resources.about_contact_address
import net.jolabs40.tvslim.windows.resources.about_data_folder
import net.jolabs40.tvslim.windows.resources.about_description
import net.jolabs40.tvslim.windows.resources.about_license
import net.jolabs40.tvslim.windows.resources.about_open_source
import net.jolabs40.tvslim.windows.resources.about_source
import net.jolabs40.tvslim.windows.resources.about_support
import net.jolabs40.tvslim.windows.resources.about_version
import net.jolabs40.tvslim.windows.resources.about_website
import net.jolabs40.tvslim.windows.resources.app_name
import net.jolabs40.tvslim.windows.resources.baseline_folder_open_24
import net.jolabs40.tvslim.windows.resources.baseline_language_24
import net.jolabs40.tvslim.windows.resources.baseline_mail_24
import net.jolabs40.tvslim.windows.resources.baseline_open_in_new_24
import net.jolabs40.tvslim.windows.resources.baseline_system_update_24
import net.jolabs40.tvslim.windows.resources.ic_tvslim
import net.jolabs40.tvslim.windows.resources.update_auto_check
import net.jolabs40.tvslim.windows.resources.update_available
import net.jolabs40.tvslim.windows.resources.update_check_now
import net.jolabs40.tvslim.windows.resources.update_checking
import net.jolabs40.tvslim.windows.resources.update_dev
import net.jolabs40.tvslim.windows.resources.update_download_page
import net.jolabs40.tvslim.windows.resources.update_downloading
import net.jolabs40.tvslim.windows.resources.update_install
import net.jolabs40.tvslim.windows.resources.update_installing
import net.jolabs40.tvslim.windows.resources.update_later
import net.jolabs40.tvslim.windows.resources.update_portable
import net.jolabs40.tvslim.windows.resources.update_up_to_date
import net.jolabs40.tvslim.windows.resources.update_verifying
import net.jolabs40.tvslim.windows.resources.update_whats_new
import net.jolabs40.tvslim.windows.ui.components.SecondaryText
import net.jolabs40.tvslim.windows.ui.components.textOf
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val UpdatePhase.busy: Boolean
    get() = this is UpdatePhase.Checking || this is UpdatePhase.Downloading ||
        this is UpdatePhase.Verification || this is UpdatePhase.Installation

/**
 * New version banner at the top of the window. It downloads nothing on its own: it offers, tracks the install
 * once requested, and goes quiet after "Later".
 */
@Composable
fun UpdateBanner(
    state: UpdateState,
    onInstall: () -> Unit,
    onPage: () -> Unit,
    onLater: () -> Unit,
) {
    val update = state.currentUpdate ?: return
    val phase = state.phase
    if (state.bannerDismissed && phase is UpdatePhase.Available) return
    var notesVisible by remember(update.version) { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painter = painterResource(Res.drawable.baseline_system_update_24), contentDescription = null)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(Res.string.update_available, update.version.toString()),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    when (phase) {
                        is UpdatePhase.Downloading -> {
                            Text(stringResource(Res.string.update_downloading), style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { phase.progress }, modifier = Modifier.fillMaxWidth())
                        }

                        is UpdatePhase.Verification -> {
                            Text(stringResource(Res.string.update_verifying), style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        is UpdatePhase.Installation ->
                            Text(stringResource(Res.string.update_installing), style = MaterialTheme.typography.bodySmall)

                        is UpdatePhase.Failure -> Text(
                            text = textOf(phase.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        else -> Unit
                    }
                }
                if (!phase.busy) {
                    if (update.notes.isNotBlank()) {
                        TextButton(onClick = { notesVisible = !notesVisible }) {
                            Text(stringResource(Res.string.update_whats_new))
                        }
                    }
                    if (state.mode == DistributionMode.INSTALLED) {
                        Button(onClick = onInstall) { Text(stringResource(Res.string.update_install)) }
                    } else {
                        OutlinedButton(onClick = onPage) { Text(stringResource(Res.string.update_download_page)) }
                    }
                    if (phase is UpdatePhase.Available) {
                        TextButton(onClick = onLater) { Text(stringResource(Res.string.update_later)) }
                    }
                }
            }
            if (notesVisible) {
                SelectionContainer {
                    Text(
                        text = update.notes,
                        modifier = Modifier.padding(start = 36.dp, top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (state.mode == DistributionMode.PORTABLE && !phase.busy) {
                Text(
                    text = stringResource(Res.string.update_portable),
                    modifier = Modifier.padding(start = 36.dp, top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** About dialog: version, license, links, data folder, and the update setting. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutDialog(
    state: UpdateState,
    onClose: () -> Unit,
    onCheck: () -> Unit,
    onVerificationAuto: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onSite: () -> Unit,
    onContact: () -> Unit,
    onSource: () -> Unit,
    onSupport: () -> Unit,
    onFolder: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        icon = {
            Image(
                painter = painterResource(Res.drawable.ic_tvslim),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
        },
        title = { Text(stringResource(Res.string.app_name)) },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(Res.string.about_version, state.currentVersion),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(text = stringResource(Res.string.about_description), style = MaterialTheme.typography.bodyMedium)
                Text(text = stringResource(Res.string.about_open_source), style = MaterialTheme.typography.bodyMedium)
                SecondaryText(stringResource(Res.string.about_license, AppInfo.LICENSE), small = true)

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSite) {
                        Icon(painterResource(Res.drawable.baseline_language_24), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.about_website))
                    }
                    TextButton(onClick = onContact) {
                        Icon(painterResource(Res.drawable.baseline_mail_24), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.about_contact, stringResource(Res.string.about_contact_address)))
                    }
                    TextButton(onClick = onSource) {
                        Icon(painterResource(Res.drawable.baseline_open_in_new_24), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.about_source))
                    }
                    TextButton(onClick = onSupport) {
                        KofiSymbol(contentDescription = null, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.about_support))
                    }
                    TextButton(onClick = onFolder) {
                        Icon(painterResource(Res.drawable.baseline_folder_open_24), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.about_data_folder))
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.clickable { onVerificationAuto(!state.autoCheck) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = state.autoCheck, onCheckedChange = onVerificationAuto)
                    Text(stringResource(Res.string.update_auto_check))
                }

                val phase = state.phase
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onCheck, enabled = !phase.busy) {
                        Text(stringResource(Res.string.update_check_now))
                    }
                    when (phase) {
                        UpdatePhase.Checking -> {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(Res.string.update_checking))
                        }

                        UpdatePhase.UpToDate -> Text(
                            text = stringResource(Res.string.update_up_to_date),
                            color = MaterialTheme.colorScheme.primary,
                        )

                        is UpdatePhase.Available -> Text(
                            text = stringResource(Res.string.update_available, phase.update.version.toString()),
                            color = MaterialTheme.colorScheme.primary,
                        )

                        is UpdatePhase.Failure -> Text(
                            text = textOf(phase.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        else -> Unit
                    }
                }

                if (phase is UpdatePhase.Available) {
                    Button(onClick = onInstall) {
                        Text(
                            stringResource(
                                if (state.mode == DistributionMode.INSTALLED) Res.string.update_install else Res.string.update_download_page,
                            ),
                        )
                    }
                }

                when (state.mode) {
                    DistributionMode.DEVELOPMENT -> SecondaryText(stringResource(Res.string.update_dev), small = true)
                    DistributionMode.PORTABLE -> SecondaryText(stringResource(Res.string.update_portable), small = true)
                    DistributionMode.INSTALLED -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(Res.string.about_close)) }
        },
    )
}
