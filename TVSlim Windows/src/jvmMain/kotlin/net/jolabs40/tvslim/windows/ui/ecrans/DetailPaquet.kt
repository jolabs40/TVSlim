package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.origine
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.baseline_info_24
import net.jolabs40.tvslim.windows.ressources.baseline_warning_24
import net.jolabs40.tvslim.windows.ressources.packages_detail_category
import net.jolabs40.tvslim.windows.ressources.packages_detail_hint
import net.jolabs40.tvslim.windows.ressources.packages_detail_origin
import net.jolabs40.tvslim.windows.ressources.packages_detail_risk
import net.jolabs40.tvslim.windows.ressources.packages_detail_selected
import net.jolabs40.tvslim.windows.ressources.packages_detail_size
import net.jolabs40.tvslim.windows.ressources.packages_detail_state
import net.jolabs40.tvslim.windows.ressources.packages_reactivate
import net.jolabs40.tvslim.windows.ressources.packages_requires_launcher
import net.jolabs40.tvslim.windows.ressources.side_effect_prefix
import net.jolabs40.tvslim.windows.ressources.size_mb
import net.jolabs40.tvslim.windows.ressources.state_disabled
import net.jolabs40.tvslim.windows.ressources.state_enabled
import net.jolabs40.tvslim.windows.ui.LignePaquet
import net.jolabs40.tvslim.windows.ui.composants.IconeOrigine
import net.jolabs40.tvslim.windows.ui.composants.LigneValeur
import net.jolabs40.tvslim.windows.ui.composants.libelleOrigine
import net.jolabs40.tvslim.windows.ui.composants.PastilleRisque
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import net.jolabs40.tvslim.windows.ui.composants.libelleRisque
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Tout ce que le catalogue sait d'un paquet, lisible d'un coup d'œil, avec l'action qui s'y rapporte. */
@Composable
fun DetailPaquet(
    ligne: LignePaquet?,
    catalogue: Catalogue,
    onBasculer: (String) -> Unit,
    onReactiver: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.padding(24.dp)) {
        if (ligne == null) {
            TexteSecondaire(
                stringResource(Res.string.packages_detail_hint),
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        val entree = ligne.entree

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = entree.nom, style = MaterialTheme.typography.headlineSmall)
            SelectionContainer {
                Text(
                    text = entree.paquet,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (ligne.etat) {
                EtatPaquet.ACTIF -> Row(
                    modifier = Modifier.clickable { onBasculer(entree.paquet) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = ligne.selectionne, onCheckedChange = { onBasculer(entree.paquet) })
                    Text(stringResource(Res.string.packages_detail_selected))
                }

                EtatPaquet.DESACTIVE -> Button(onClick = { onReactiver(entree.paquet) }) {
                    Text(stringResource(Res.string.packages_reactivate))
                }

                EtatPaquet.ABSENT -> Unit
            }

            HorizontalDivider()

            LigneValeur(
                stringResource(Res.string.packages_detail_state),
                stringResource(if (ligne.etat == EtatPaquet.DESACTIVE) Res.string.state_disabled else Res.string.state_enabled),
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TexteSecondaire(stringResource(Res.string.packages_detail_risk), modifier = Modifier.weight(1f))
                PastilleRisque(entree.risque)
                Spacer(Modifier.width(8.dp))
                Text(text = libelleRisque(entree.risque), style = MaterialTheme.typography.bodyMedium)
            }
            LigneValeur(stringResource(Res.string.packages_detail_category), catalogue.nomCategorie(entree.categorie))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TexteSecondaire(stringResource(Res.string.packages_detail_origin), modifier = Modifier.weight(1f))
                IconeOrigine(entree.origine)
                Spacer(Modifier.width(8.dp))
                Text(text = libelleOrigine(entree.origine), style = MaterialTheme.typography.bodyMedium)
            }
            entree.tailleMo?.let { taille ->
                LigneValeur(stringResource(Res.string.packages_detail_size), stringResource(Res.string.size_mb, taille))
            }

            HorizontalDivider()

            Text(text = entree.description, style = MaterialTheme.typography.bodyLarge)

            entree.effetDeBord?.let { effet ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        painter = painterResource(Res.drawable.baseline_warning_24),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(Res.string.side_effect_prefix, effet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (entree.requiertLauncherTiers) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        painter = painterResource(Res.drawable.baseline_info_24),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TexteSecondaire(stringResource(Res.string.packages_requires_launcher))
                }
            }
        }
    }
}
