package net.jolabs40.tvslim.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jolabs40.tvslim.R
import net.jolabs40.tvslim.privileged.EtatPrivilege
import net.jolabs40.tvslim.ui.EtatUi
import net.jolabs40.tvslim.ui.components.Bandeau
import net.jolabs40.tvslim.ui.components.Bloc
import net.jolabs40.tvslim.ui.components.EnTete
import net.jolabs40.tvslim.ui.components.LigneMesure

@Composable
fun AccueilScreen(
    etat: EtatUi,
    onPaquets: () -> Unit,
    onReglages: () -> Unit,
    onJournal: () -> Unit,
    onActualiser: () -> Unit,
    onAutoriser: () -> Unit,
    onInstallerShizuku: () -> Unit,
    onFermerMessage: () -> Unit,
) {
    val premierBouton = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { premierBouton.requestFocus() } }

    Column(modifier = Modifier.fillMaxWidth()) {
        EnTete(
            titre = stringResource(R.string.app_name),
            sousTitre = stringResource(R.string.home_subtitle),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Bloc(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.device_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                LigneMesure(
                    stringResource(R.string.device_model),
                    "${etat.infos.marque} ${etat.infos.modele}",
                )
                LigneMesure(stringResource(R.string.device_android), etat.infos.versionAndroid)
                LigneMesure(
                    stringResource(R.string.device_memory),
                    stringResource(
                        R.string.device_memory_value,
                        etat.infos.memoireLibreMo,
                        etat.infos.memoireTotaleMo,
                    ),
                )
                LigneMesure(
                    stringResource(R.string.device_packages_active),
                    etat.infos.paquetsInstalles.toString(),
                )
                LigneMesure(
                    stringResource(R.string.device_packages_disabled),
                    etat.infos.paquetsDesactives.toString(),
                )
                LigneMesure(
                    stringResource(R.string.device_home),
                    etat.infos.accueilActuel.ifBlank { "—" },
                )
            }

            Bloc(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.privileges_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(libellePrivilege(etat.privilege)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = couleurPrivilege(etat.privilege),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(consignePrivilege(etat.privilege)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (etat.privilege) {
                    EtatPrivilege.ABSENT, EtatPrivilege.SERVICE_ARRETE -> {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onInstallerShizuku) {
                            Text(stringResource(R.string.action_install_shizuku))
                        }
                    }

                    EtatPrivilege.AUTORISATION_REQUISE, EtatPrivilege.REFUSE -> {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onAutoriser) {
                            Text(stringResource(R.string.action_grant_privileges))
                        }
                    }

                    else -> Unit
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPaquets, modifier = Modifier.focusRequester(premierBouton)) {
                Text(stringResource(R.string.action_packages))
            }
            Button(onClick = onReglages) { Text(stringResource(R.string.action_settings)) }
            Button(onClick = onJournal) { Text(stringResource(R.string.action_journal)) }
            Button(onClick = onActualiser) { Text(stringResource(R.string.action_refresh)) }
        }

        etat.message?.let { message ->
            Spacer(Modifier.height(16.dp))
            Bandeau(message = message, onFermer = onFermerMessage)
        }

        if (etat.chargement || etat.travailEnCours) {
            Text(
                text = stringResource(R.string.common_working),
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun libellePrivilege(etat: EtatPrivilege): Int = when (etat) {
    EtatPrivilege.INCONNU -> R.string.privilege_unknown
    EtatPrivilege.ABSENT -> R.string.privilege_absent
    EtatPrivilege.SERVICE_ARRETE -> R.string.privilege_service_stopped
    EtatPrivilege.AUTORISATION_REQUISE -> R.string.privilege_permission_required
    EtatPrivilege.REFUSE -> R.string.privilege_refused
    EtatPrivilege.PRET -> R.string.privilege_ready
}

/** Vert quand les commandes privilégiées peuvent partir, rouge dès qu'une action est requise. */
@Composable
internal fun couleurPrivilege(etat: EtatPrivilege): Color = when (etat) {
    EtatPrivilege.PRET -> MaterialTheme.colorScheme.primary
    EtatPrivilege.INCONNU -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.error
}

internal fun consignePrivilege(etat: EtatPrivilege): Int = when (etat) {
    EtatPrivilege.ABSENT -> R.string.privilege_hint_absent
    EtatPrivilege.SERVICE_ARRETE -> R.string.privilege_hint_service
    EtatPrivilege.AUTORISATION_REQUISE, EtatPrivilege.REFUSE -> R.string.privilege_hint_permission
    EtatPrivilege.PRET -> R.string.privilege_hint_ready
    EtatPrivilege.INCONNU -> R.string.privilege_hint_unknown
}
