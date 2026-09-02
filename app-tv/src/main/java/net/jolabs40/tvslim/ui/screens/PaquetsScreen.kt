package net.jolabs40.tvslim.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jolabs40.tvslim.R
import net.jolabs40.tvslim.catalog.Risque
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.ui.EtatUi
import net.jolabs40.tvslim.ui.LignePaquetTv
import net.jolabs40.tvslim.ui.components.BarreDefilement
import net.jolabs40.tvslim.ui.components.Bloc
import net.jolabs40.tvslim.ui.components.CompteurListe
import net.jolabs40.tvslim.ui.components.EnTete
import net.jolabs40.tvslim.ui.components.LigneFocusable
import net.jolabs40.tvslim.ui.components.PastilleRisque

/**
 * Ce que le catalogue connaît, et l'état de chaque entrée sur ce téléviseur — **en lecture
 * seule**. Activer ou désactiver se fait depuis le compagnon : cocher cinquante-six entrées à
 * la télécommande est précisément ce qu'on cherchait à éviter.
 *
 * Disposition en deux volets, comme le veut la télévision : une liste compacte à gauche, et le
 * détail de l'élément focalisé à droite. Rien à faire défiler dans un texte, tout est lisible
 * de loin.
 */
@Composable
fun PaquetsScreen(etat: EtatUi) {
    val lignes = etat.paquetsPresents
    val etatListe = rememberLazyListState()
    var apercu by remember { mutableStateOf<LignePaquetTv?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        EnTete(
            titre = stringResource(R.string.packages_title),
            sousTitre = stringResource(R.string.packages_subtitle),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(
                    R.string.packages_counts,
                    lignes.size,
                    etat.paquetsDesactives,
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            CompteurListe(
                etat = etatListe,
                total = lignes.size,
                indexCourant = lignes.indexOfFirst { it.entree.paquet == apercu?.entree?.paquet },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LazyColumn(
                state = etatListe,
                modifier = Modifier.weight(1.3f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(lignes, key = { it.entree.paquet }) { ligne ->
                    LigneFocusable(
                        onClick = { apercu = ligne },
                        modifier = Modifier.onFocusChanged { focus ->
                            if (focus.isFocused) apercu = ligne
                        },
                    ) {
                        LigneCompacte(ligne)
                    }
                }
            }

            BarreDefilement(etat = etatListe, modifier = Modifier.width(3.dp))

            Bloc(modifier = Modifier.weight(1f)) {
                Apercu(ligne = apercu)
            }
        }
    }
}

/** Une ligne de liste : juste de quoi reconnaître l'entrée et son état. */
@Composable
private fun LigneCompacte(ligne: LignePaquetTv) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PastilleRisque(ligne.entree.risque, Modifier.padding(end = 10.dp))
        Text(
            text = ligne.entree.nom,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(
                if (ligne.etat == EtatPaquet.DESACTIVE) {
                    R.string.state_disabled
                } else {
                    R.string.state_active
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (ligne.etat == EtatPaquet.DESACTIVE) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

/** Volet de droite : tout ce que le catalogue sait de l'entrée survolée. */
@Composable
private fun Apercu(ligne: LignePaquetTv?) {
    if (ligne == null) {
        Text(
            text = stringResource(R.string.packages_browse_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        text = ligne.entree.nom,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = ligne.entree.paquet,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = ligne.entree.description,
        style = MaterialTheme.typography.bodyMedium,
    )
    ligne.entree.effetDeBord?.let { effet ->
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.side_effect_prefix, effet),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(14.dp))
    Detail(
        stringResource(R.string.packages_state),
        stringResource(
            if (ligne.etat == EtatPaquet.DESACTIVE) {
                R.string.state_disabled
            } else {
                R.string.state_active
            },
        ),
    )
    Detail(stringResource(R.string.packages_risk), libelleRisque(ligne.entree.risque))
    ligne.entree.tailleMo?.let { taille ->
        Detail(stringResource(R.string.packages_size), stringResource(R.string.size_mb, taille))
    }
    if (ligne.entree.marque.isNotBlank()) {
        Detail(stringResource(R.string.packages_origin), ligne.entree.marque)
    }
}

@Composable
private fun Detail(libelle: String, valeur: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = libelle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = valeur, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun libelleRisque(risque: Risque): String = stringResource(
    when (risque) {
        Risque.AUCUN -> R.string.risk_none
        Risque.FAIBLE -> R.string.risk_low
        Risque.MOYEN -> R.string.risk_medium
        Risque.ELEVE -> R.string.risk_high
    },
)

