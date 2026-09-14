package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.catalog.Profil
import net.jolabs40.tvslim.catalog.Risque
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.PropositionCatalogue
import net.jolabs40.tvslim.device.origine
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.EtatRemote
import net.jolabs40.tvslim.remote.ui.Filtre
import net.jolabs40.tvslim.remote.ui.LignePaquet
import net.jolabs40.tvslim.remote.ui.theme.CouleursRisque

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaquetsScreen(
    etat: EtatRemote,
    onBasculer: (String) -> Unit,
    onProfil: (Profil) -> Unit,
    onToutDecocher: () -> Unit,
    onAppliquer: () -> Unit,
    onReactiver: (String) -> Unit,
    onRecherche: (String) -> Unit,
    onFiltre: (Filtre) -> Unit,
    onSauvegarder: () -> Unit,
    onReinjecter: () -> Unit,
    onExporterInconnus: () -> Unit,
    onProposerInconnus: () -> Unit,
) {
    if (!etat.connecte) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                text = stringResource(R.string.packages_not_connected),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val affichees = etat.affichees

    Column(modifier = Modifier.fillMaxWidth()) {
        etat.progression?.let { progression ->
            LinearProgressIndicator(
                progress = {
                    if (progression.total == 0) 0f else progression.fait.toFloat() / progression.total
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(
                    R.string.packages_progress,
                    progression.fait,
                    progression.total,
                ),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        OutlinedTextField(
            value = etat.recherche,
            onValueChange = onRecherche,
            label = { Text(stringResource(R.string.packages_search)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        // Tous, actifs ou désactivés : un seul interrupteur à trois positions plutôt que trois puces.
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            Filtre.entries.forEachIndexed { rang, choix ->
                SegmentedButton(
                    selected = etat.filtre == choix,
                    onClick = { onFiltre(choix) },
                    shape = SegmentedButtonDefaults.itemShape(index = rang, count = Filtre.entries.size),
                    // Sans coche : trois libellés et leurs compteurs tiennent à peine sur un téléphone.
                    icon = {},
                ) {
                    Text(
                        text = when (choix) {
                            Filtre.TOUS -> stringResource(R.string.filter_all)
                            Filtre.ACTIFS -> stringResource(R.string.filter_enabled, etat.nombreActifs)
                            Filtre.DESACTIVES -> stringResource(R.string.filter_disabled, etat.nombreDesactives)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        LazyRow(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(etat.catalogue.profils) { profil ->
                AssistChip(onClick = { onProfil(profil) }, label = { Text(profil.nom) })
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onAppliquer, enabled = !etat.travailEnCours) {
                Text(stringResource(R.string.packages_apply, etat.selection.size))
            }
            TextButton(onClick = onToutDecocher) {
                Text(stringResource(R.string.packages_clear))
            }
        }

        // La configuration — launcher et paquets — se sauvegarde et se réinjecte à côté des profils :
        // une sauvegarde est un profil qu'on s'est fait soi-même.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onSauvegarder,
                enabled = !etat.travailEnCours,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.config_save), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = onReinjecter,
                enabled = !etat.travailEnCours,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.config_reinject), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

        val inconnus = etat.inconnusAffiches
        if (affichees.isEmpty() && inconnus.isEmpty()) {
            Text(
                text = stringResource(R.string.packages_none_matching),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(affichees, key = { it.entree.paquet }) { ligne ->
                VuePaquet(
                    ligne = ligne,
                    onClick = {
                        if (ligne.etat == EtatPaquet.DESACTIVE) {
                            onReactiver(ligne.entree.paquet)
                        } else {
                            onBasculer(ligne.entree.paquet)
                        }
                    },
                )
            }
            // Après le catalogue, ce qu'il ne connaît pas : montré, jamais proposé à la désactivation.
            if (etat.inconnus.isNotEmpty()) {
                sectionInconnus(
                    affiches = inconnus,
                    total = etat.inconnus.size,
                    onExporter = onExporterInconnus,
                    onProposer = onProposerInconnus.takeIf { PropositionCatalogue.aProposer(etat.inconnus) },
                )
            }
        }
    }
}

@Composable
private fun VuePaquet(ligne: LignePaquet, onClick: () -> Unit) {
    val desactive = ligne.etat == EtatPaquet.DESACTIVE
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (desactive) {
            TextButton(onClick = onClick) { Text(stringResource(R.string.packages_reactivate)) }
        } else {
            Checkbox(checked = ligne.selectionne, onCheckedChange = { onClick() })
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconeOrigine(ligne.entree.origine, modifier = Modifier.padding(end = 8.dp))
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(couleur(ligne.entree.risque), CircleShape),
                )
                Text(
                    text = ligne.entree.nom,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                ligne.entree.tailleMo?.let { taille ->
                    Text(
                        text = stringResource(R.string.size_mb, taille),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = ligne.entree.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Décrit d'après un inventaire envoyé : il se coche à la main, jamais par un profil.
            if (!ligne.entree.eprouve) {
                Text(
                    text = stringResource(R.string.packages_untested),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            ligne.entree.effetDeBord?.let { effet ->
                Text(
                    text = stringResource(R.string.side_effect_prefix, effet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun couleur(risque: Risque) = when (risque) {
    Risque.AUCUN -> CouleursRisque.aucun
    Risque.FAIBLE -> CouleursRisque.faible
    Risque.MOYEN -> CouleursRisque.moyen
    Risque.ELEVE -> CouleursRisque.eleve
}
