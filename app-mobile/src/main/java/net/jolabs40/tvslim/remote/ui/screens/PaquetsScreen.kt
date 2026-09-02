package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.catalog.Profil
import net.jolabs40.tvslim.catalog.Risque
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.EtatRemote
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

    val parCategorie = etat.presentes.groupBy { it.entree.categorie }

    Column(modifier = Modifier.fillMaxWidth()) {
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

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            parCategorie.forEach { (categorie, lignes) ->
                item(key = "cat-$categorie") {
                    Text(
                        text = etat.catalogue.nomCategorie(categorie),
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(lignes, key = { it.entree.paquet }) { ligne ->
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
