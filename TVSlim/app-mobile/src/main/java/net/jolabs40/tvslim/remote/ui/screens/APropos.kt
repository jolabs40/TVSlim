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
import net.jolabs40.tvslim.applicationtv.ChoixPublicationTv
import net.jolabs40.tvslim.remote.BuildConfig
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.soutien.InvitationSoutien

/** La barre du haut : ouvre « À propos », depuis chaque onglet. */
@Composable
fun BoutonAPropos(onOuvrir: () -> Unit) {
    IconButton(onClick = onOuvrir) {
        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.about_title))
    }
}

/**
 * Version, licence, site, contact, code source et soutien — la fenêtre « À propos » de Windows, sans ce qui n'y
 * concerne que l'ordinateur (dossier des données, mises à jour). Le site s'ouvre dans la langue de l'application.
 */
@Composable
fun AProposDialogue(onFermer: () -> Unit) {
    val liens = LocalUriHandler.current
    val site = stringResource(R.string.about_website_url)
    val contact = stringResource(R.string.about_contact_address)
    AlertDialog(
        onDismissRequest = onFermer,
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
                    text = stringResource(R.string.about_license, LICENCE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Les uns sous les autres : un téléphone n'a pas la largeur d'en aligner trois.
                Column {
                    TextButton(onClick = { runCatching { liens.openUri(site) } }) {
                        Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.about_website))
                    }
                    // Sans messagerie sur le téléphone, rien ne s'ouvre : l'adresse reste lisible sur le bouton.
                    TextButton(onClick = { runCatching { liens.openUri("mailto:$contact") } }) {
                        Icon(Icons.Filled.Mail, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.about_contact, contact))
                    }
                    TextButton(onClick = { runCatching { liens.openUri(CODE_SOURCE) } }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.about_source))
                    }
                    TextButton(onClick = { runCatching { liens.openUri(InvitationSoutien.LIEN) } }) {
                        SymboleKofi(contentDescription = null, taille = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.about_support))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onFermer) { Text(stringResource(R.string.about_close)) }
        },
    )
}

/** Comme `licenceApp` de la version Windows. */
private const val LICENCE = "Apache-2.0"

private const val CODE_SOURCE = "https://github.com/${ChoixPublicationTv.DEPOT}"
