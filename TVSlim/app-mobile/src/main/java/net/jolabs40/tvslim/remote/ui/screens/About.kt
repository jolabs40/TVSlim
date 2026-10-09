package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.tvapp.TvReleaseChoice
import net.jolabs40.tvslim.remote.BuildConfig
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.support.SupportInvitation

/** Top bar button, shown on every tab. */
@Composable
fun AboutButton(onOpen: () -> Unit) {
    IconButton(onClick = onOpen) {
        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.about_title))
    }
}

/** The Windows About window minus its PC-only parts (data folder, updates). The site URL is localized. */
@Composable
fun AboutDialog(onClose: () -> Unit) {
    val links = LocalUriHandler.current
    val site = stringResource(R.string.about_website_url)
    val contact = stringResource(R.string.about_contact_address)
    AlertDialog(
        onDismissRequest = onClose,
        icon = {
            Image(painter = painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(56.dp))
        },
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(text = stringResource(R.string.about_description), style = MaterialTheme.typography.bodyMedium)
                Text(text = stringResource(R.string.about_open_source), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.about_license, LICENSE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Stacked: a phone is too narrow to fit them in a row.
                Column {
                    TextButton(onClick = { runCatching { links.openUri(site) } }) {
                        Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.about_website))
                    }
                    // Nothing opens without a mail app, so the address is printed on the button.
                    TextButton(onClick = { runCatching { links.openUri("mailto:$contact") } }) {
                        Icon(Icons.Filled.Mail, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.about_contact, contact))
                    }
                    TextButton(onClick = { runCatching { links.openUri(CODE_SOURCE) } }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.about_source))
                    }
                    TextButton(onClick = { runCatching { links.openUri(SupportInvitation.LINK) } }) {
                        KofiSymbol(contentDescription = null, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.about_support))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.about_close)) }
        },
    )
}

/** Keep in sync with `appLicense` in the Windows build. */
private const val LICENSE = "Apache-2.0"

private const val CODE_SOURCE = "https://github.com/${TvReleaseChoice.REPOSITORY}"
