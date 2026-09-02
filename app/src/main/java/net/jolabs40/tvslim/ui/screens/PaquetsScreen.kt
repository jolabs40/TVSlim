package net.jolabs40.tvslim.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jolabs40.tvslim.R
import net.jolabs40.tvslim.catalog.Profil
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.ui.EtatUi
import net.jolabs40.tvslim.ui.LignePaquet
import net.jolabs40.tvslim.ui.components.Bandeau
import net.jolabs40.tvslim.ui.components.EnTete
import net.jolabs40.tvslim.ui.components.LigneFocusable
import net.jolabs40.tvslim.ui.components.PastilleRisque

@Composable
fun PaquetsScreen(
    etat: EtatUi,
    onBasculer: (String) -> Unit,
    onProfil: (Profil) -> Unit,
    onToutDecocher: () -> Unit,
    onAppliquer: () -> Unit,
    onReactiver: (String) -> Unit,
    onFermerMessage: () -> Unit,
) {
    val parCategorie = etat.presentes.groupBy { it.entree.categorie }

    Column(modifier = Modifier.fillMaxWidth()) {
        EnTete(
            titre = stringResource(R.string.packages_title),
            sousTitre = stringResource(R.string.packages_subtitle),
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(etat.catalogue.profils) { profil ->
                Button(onClick = { onProfil(profil) }) { Text(profil.nom) }
            }
            item { Button(onClick = onToutDecocher) { Text(stringResource(R.string.packages_clear)) } }
            item {
                Button(onClick = onAppliquer) {
                    Text(stringResource(R.string.packages_apply, etat.selection.size))
                }
            }
        }

        etat.message?.let { message ->
            Spacer(Modifier.height(12.dp))
            Bandeau(message = message, onFermer = onFermerMessage)
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            parCategorie.forEach { (categorie, lignes) ->
                item(key = "cat-$categorie") {
                    Text(
                        text = etat.catalogue.nomCategorie(categorie),
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
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
    LigneFocusable(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = marqueur(ligne),
                modifier = Modifier.padding(end = 14.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PastilleRisque(ligne.entree.risque, Modifier.padding(end = 8.dp))
                    Text(
                        text = ligne.entree.nom,
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
            Text(
                text = stringResource(
                    if (ligne.etat == EtatPaquet.DESACTIVE) {
                        R.string.state_disabled
                    } else {
                        R.string.state_active
                    },
                ),
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun marqueur(ligne: LignePaquet): String = when {
    ligne.etat == EtatPaquet.DESACTIVE -> "○"
    ligne.selectionne -> "☑"
    else -> "☐"
}
