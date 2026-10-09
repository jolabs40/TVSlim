package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import net.jolabs40.tvslim.device.Fabricant
import net.jolabs40.tvslim.device.TypeAppareil
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.adb.ConnexionUi
import net.jolabs40.tvslim.remote.adb.EtatConnexion
import net.jolabs40.tvslim.remote.adb.ProblemeConnexion
import net.jolabs40.tvslim.remote.ui.ActionsCommande
import net.jolabs40.tvslim.remote.ui.ActionsPermissions
import net.jolabs40.tvslim.remote.ui.ActionsShizuku
import net.jolabs40.tvslim.remote.ui.EtatRemote

/** The scanner UI module is not in the APK: Play services downloads it. */
private enum class EtatModule { INCONNU, TELECHARGEMENT, PRET, INDISPONIBLE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnexionScreen(
    etat: EtatRemote,
    onHote: (String) -> Unit,
    onPort: (String) -> Unit,
    onConnecter: () -> Unit,
    onDeconnecter: () -> Unit,
    onActualiser: () -> Unit,
    onRedemarrer: () -> Unit,
    onScan: (String) -> Unit,
    onEchecScan: (String) -> Unit,
    onInstallerLauncher: (String) -> Unit,
    onDefinirAccueil: (String) -> Unit,
    onReprendreDerive: () -> Unit,
    onChercher: () -> Unit,
    onArreterRecherche: () -> Unit,
    onConnecterA: (net.jolabs40.tvslim.remote.adb.AppareilDecouvert) -> Unit,
    actionsPermissions: ActionsPermissions,
    etatApplications: net.jolabs40.tvslim.remote.ui.EtatApplications,
    etatApplicationTv: net.jolabs40.tvslim.remote.ui.EtatApplicationTvUi,
    actionsApplicationTv: net.jolabs40.tvslim.remote.ui.ActionsApplicationTv,
    onChoisirApk: () -> Unit,
    actionsCommande: ActionsCommande,
    actionsShizuku: ActionsShizuku,
) {
    val contexte = LocalContext.current
    val options = remember {
        GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    }
    val scanner = remember { GmsBarcodeScanning.getClient(contexte, options) }
    var etatModule by remember { mutableStateOf(EtatModule.INCONNU) }

    // Requested when the screen opens, not on first tap: otherwise the first scan waits for the download, stuck on
    // "Waiting for the Barcode UI module to be downloaded".
    LaunchedEffect(Unit) {
        val installateur = ModuleInstall.getClient(contexte)
        installateur.areModulesAvailable(scanner)
            .addOnSuccessListener { reponse ->
                if (reponse.areModulesAvailable()) {
                    etatModule = EtatModule.PRET
                } else {
                    etatModule = EtatModule.TELECHARGEMENT
                    installateur
                        .installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
                        .addOnSuccessListener { etatModule = EtatModule.PRET }
                        .addOnFailureListener { etatModule = EtatModule.INDISPONIBLE }
                }
            }
            .addOnFailureListener { etatModule = EtatModule.INDISPONIBLE }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.connection_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.connection_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (etat.connecte) {
            // Actions before measurements, so the useful button needs no scrolling. Drift comes first of all.
            etat.derive?.let { plan -> CarteDerive(plan = plan, onReprendre = onReprendreDerive) }
            CarteAccueil(etat = etat, onInstaller = onInstallerLauncher, onDefinirAccueil = onDefinirAccueil)
            // TV or box only: the TV app has no use on a phone connected for testing.
            val televiseur = etat.infos.typeAppareil == TypeAppareil.TELEVISEUR || etat.infos.typeAppareil == TypeAppareil.BOX
            AppareilConnecte(
                etat = etat,
                onDeconnecter = onDeconnecter,
                onRedemarrer = onRedemarrer,
                // Also re-reads the TV app, which may have changed on the TV.
                onActualiser = { onActualiser(); if (televiseur) actionsApplicationTv.onLire() },
            )
            if (televiseur) {
                CarteApplicationTv(etat = etatApplicationTv, hote = etat.connexion.hote, actions = actionsApplicationTv)
            }
            // Last and collapsed: rarely used tools unrelated to debloating.
            OutilsAvances(
                occupe = etat.permissions.lecture || etat.installation.occupee ||
                    etat.commande.enCours || etat.shizuku.enCours,
            ) {
                CartePermissions(etat = etat.permissions, applications = etatApplications, actions = actionsPermissions)
                CarteInstallation(etat = etat.installation, onChoisir = onChoisirApk)
                CarteCommande(etat = etat.commande, actions = actionsCommande)
                CarteShizuku(etat = etat.shizuku, actions = actionsShizuku)
            }
            return@Column
        }

        if (etat.redemarrage) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(text = stringResource(R.string.reboot_in_progress), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Discovery follows the lifecycle, not composition: leaving the app keeps the Activity and its tree alive, and
        // discovery would keep listening for three service types, radio awake for nothing. ON_START starts, ON_STOP stops.
        LifecycleStartEffect(Unit) {
            onChercher()
            onStopOrDispose { onArreterRecherche() }
        }

        if (etat.detectes.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.discovery_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.discovery_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    etat.detectes.forEach { appareil ->
                        // For a device seen before, its remembered name starts with the brand.
                        val fabricant = etat.nomsConnus[appareil.hote]?.let { Fabricant.depuisNom(it) }
                        OutlinedButton(
                            onClick = { onConnecterA(appareil) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (fabricant != null) {
                                PlaqueMarque(fabricant = fabricant, hauteur = 26.dp)
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = appareil.libelle,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "${appareil.hote}:${appareil.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.connection_scan_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.connection_scan_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            scanner.startScan()
                                .addOnSuccessListener { code -> code.rawValue?.let(onScan) }
                                .addOnFailureListener { erreur ->
                                    onEchecScan(erreur.message.orEmpty())
                                }
                        },
                        enabled = etatModule != EtatModule.TELECHARGEMENT,
                    ) {
                        Text(stringResource(R.string.connection_scan))
                    }
                    when (etatModule) {
                        EtatModule.TELECHARGEMENT -> {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                            Text(
                                text = stringResource(R.string.connection_scan_preparing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        EtatModule.INDISPONIBLE -> Text(
                            text = stringResource(R.string.connection_scan_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        else -> Unit
                    }
                }
            }
        }

        HorizontalDivider()

        // No field takes focus by itself, so the keyboard stays closed when the screen opens.
        Text(
            text = stringResource(R.string.connection_manual_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = etat.hoteSaisi,
                onValueChange = onHote,
                label = { Text(stringResource(R.string.connection_host)) },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = etat.portSaisi,
                onValueChange = onPort,
                label = { Text(stringResource(R.string.connection_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onConnecter,
                enabled = etat.connexion.etat != EtatConnexion.CONNEXION,
            ) {
                Text(stringResource(R.string.connection_connect))
            }
            if (etat.connexion.etat == EtatConnexion.CONNEXION) {
                CircularProgressIndicator()
            }
        }

        if (etat.connexion.etat == EtatConnexion.CONNEXION) {
            Text(
                text = stringResource(R.string.connection_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        EtatErreur(etat.connexion)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.connection_help_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.connection_help_1),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.connection_help_2),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.connection_help_3),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppareilConnecte(
    etat: EtatRemote,
    onDeconnecter: () -> Unit,
    onRedemarrer: () -> Unit,
    onActualiser: () -> Unit,
) {
    // Three buttons do not always fit on a phone: the one that overflows wraps whole instead of splitting its text.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onDeconnecter) {
            Text(stringResource(R.string.connection_disconnect))
        }
        Button(onClick = onActualiser) { Text(stringResource(R.string.action_refresh)) }
        OutlinedButton(onClick = onRedemarrer) { Text(stringResource(R.string.device_reboot)) }
        if (etat.chargement) CircularProgressIndicator()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    when (etat.infos.typeAppareil) {
                        TypeAppareil.TELEVISEUR -> R.string.device_title
                        TypeAppareil.BOX -> R.string.device_type_box
                        TypeAppareil.TELEPHONE -> R.string.device_type_phone
                        TypeAppareil.TABLETTE -> R.string.device_type_tablet
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            etat.infos.fabricant?.let {
                PlaqueMarque(fabricant = it, hauteur = 34.dp, modifier = Modifier.padding(vertical = 4.dp))
            }
            Mesure(
                stringResource(R.string.device_model),
                etat.infos.nomAffiche,
            )
            Mesure(stringResource(R.string.device_android), etat.infos.versionAndroid)
            Mesure(
                stringResource(R.string.device_memory),
                stringResource(
                    R.string.device_memory_value,
                    etat.infos.memoireLibreMo,
                    etat.infos.memoireTotaleMo,
                ),
            )
            Mesure(
                stringResource(R.string.device_packages_active),
                etat.infos.paquetsInstalles.toString(),
            )
            Mesure(
                stringResource(R.string.device_packages_disabled),
                etat.infos.paquetsDesactives.toString(),
            )
            Mesure(
                stringResource(R.string.device_home),
                etat.infos.accueilActuel.ifBlank { "—" },
            )
        }
    }
}

@Composable
private fun Mesure(libelle: String, valeur: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = libelle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = valeur, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EtatErreur(connexion: ConnexionUi) {
    if (connexion.etat != EtatConnexion.ERREUR) return
    val explication = when (connexion.probleme ?: ProblemeConnexion.AUTRE) {
        ProblemeConnexion.REFUSEE -> R.string.connection_problem_refused
        ProblemeConnexion.DELAI -> R.string.connection_problem_timeout
        ProblemeConnexion.NON_AUTORISEE -> R.string.connection_problem_unauthorized
        ProblemeConnexion.INJOIGNABLE -> R.string.connection_problem_unreachable
        ProblemeConnexion.AUTRE -> R.string.connection_problem_other
    }
    Text(
        text = stringResource(explication),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    if (connexion.detail.isNotBlank()) {
        SelectionContainer {
            Text(
                text = connexion.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
