package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import net.jolabs40.tvslim.remote.adb.EtatConnexion
import net.jolabs40.tvslim.remote.ui.ActionsCommande
import net.jolabs40.tvslim.remote.ui.ActionsPermissions
import net.jolabs40.tvslim.remote.ui.EtatRemote

/** Le module d'interface du scanner n'est pas dans l'APK : Play services le télécharge. */
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
    onScan: (String) -> Unit,
    onEchecScan: (String) -> Unit,
    onInstallerLauncher: (String) -> Unit,
    onChercher: () -> Unit,
    onArreterRecherche: () -> Unit,
    onConnecterA: (net.jolabs40.tvslim.remote.adb.AppareilDecouvert) -> Unit,
    actionsPermissions: ActionsPermissions,
    onChoisirApk: () -> Unit,
    actionsCommande: ActionsCommande,
) {
    val contexte = LocalContext.current
    val options = remember {
        GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    }
    val scanner = remember { GmsBarcodeScanning.getClient(contexte, options) }
    var etatModule by remember { mutableStateOf(EtatModule.INCONNU) }

    // Le module est réclamé dès l'ouverture de l'écran, pas au premier appui : sans cela, la
    // première lecture attend derrière un téléchargement, écran figé sur « Waiting for the
    // Barcode UI module to be downloaded ».
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
            // L'action d'abord, les mesures ensuite : un écran qu'il faut faire défiler pour
            // trouver le seul bouton utile est un écran raté.
            CarteAccueil(etat = etat, onInstaller = onInstallerLauncher)
            AppareilConnecte(etat = etat, onDeconnecter = onDeconnecter, onActualiser = onActualiser)
            // En dernier : accorder une permission privilégiée est rare, et sans rapport avec
            // le débloat. Elle n'a de sens que téléviseur joint, d'où sa place ici.
            CartePermissions(etat = etat.permissions, actions = actionsPermissions)
            CarteInstallation(etat = etat.installation, onChoisir = onChoisirApk)
            CarteCommande(etat = etat.commande, actions = actionsCommande)
            return@Column
        }

        // Le plus court des chemins quand il aboutit : l'appareil s'annonce, on le touche.
        //
        // Adossé au cycle de vie, et non à la seule composition : quitter l'application ne
        // défait pas l'arbre, l'Activity restant vivante. La découverte écoutait alors trois
        // types de services indéfiniment — de la radio réveillée pour rien pendant qu'on va
        // allumer le téléviseur. ON_START relance, ON_STOP arrête.
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
                        // La marque d'un appareil déjà joint : le nom qu'on en a retenu commence par elle.
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

        // Chemin principal : scanner le code affiché par le téléviseur.
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

        // Repli : saisie de l'adresse. Aucun champ ne prend le focus tout seul, le clavier ne
        // s'ouvre donc pas à l'arrivée sur l'écran.
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

        if (etat.connexion.message.isNotBlank()) {
            Text(
                text = etat.connexion.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

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

@Composable
private fun AppareilConnecte(
    etat: EtatRemote,
    onDeconnecter: () -> Unit,
    onActualiser: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onDeconnecter) {
            Text(stringResource(R.string.connection_disconnect))
        }
        Button(onClick = onActualiser) { Text(stringResource(R.string.action_refresh)) }
        if (etat.chargement) CircularProgressIndicator()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    if (etat.infos.typeAppareil == TypeAppareil.BOX) R.string.device_type_box else R.string.device_title,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // La marque en tête, reconnue sur ce que l'appareil déclare : voir Fabricant.
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
