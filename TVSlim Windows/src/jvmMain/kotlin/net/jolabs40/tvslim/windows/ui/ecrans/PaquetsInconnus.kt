package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.PaquetInconnu
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.state_disabled
import net.jolabs40.tvslim.windows.ressources.unknown_export
import net.jolabs40.tvslim.windows.ressources.unknown_hint
import net.jolabs40.tvslim.windows.ressources.unknown_propose
import net.jolabs40.tvslim.windows.ressources.unknown_title
import net.jolabs40.tvslim.windows.ui.composants.IconeOrigine
import net.jolabs40.tvslim.windows.ui.composants.LegendeOrigines
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.stringResource

/** La largeur de la colonne des cases, dans la liste du catalogue : les inconnus s'y alignent. */
private val COLONNE_CASES = 120.dp

/**
 * Sous le catalogue, les paquets livrés avec le téléviseur qu'il ne décrit pas : regroupés par éditeur
 * (« org.droidtv »), chacun avec son origine devinée. En lecture seule — un paquet système inconnu peut
 * porter le tuner ou la télécommande —, mais la liste s'exporte, pour compléter le catalogue, et se propose
 * à lui par le formulaire GitHub quand un paquet du constructeur y figure.
 */
fun LazyListScope.sectionInconnus(
    affiches: List<PaquetInconnu>,
    total: Int,
    onExporter: () -> Unit,
    /** Null quand aucun paquet du constructeur n'échappe au catalogue : rien qui vaille une proposition. */
    onProposer: (() -> Unit)?,
) {
    item(key = "inconnus-entete") {
        EnteteInconnus(affiches = affiches.size, total = total, onExporter = onExporter, onProposer = onProposer)
    }
    affiches.groupBy { it.famille }.forEach { (famille, membres) ->
        item(key = "inconnus-famille-$famille") {
            Text(
                text = "$famille (${membres.size})",
                modifier = Modifier.padding(start = COLONNE_CASES, top = 10.dp, bottom = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(membres, key = { "inconnu-${it.paquet}" }) { inconnu -> LigneInconnu(inconnu) }
    }
}

@Composable
private fun EnteteInconnus(affiches: Int, total: Int, onExporter: () -> Unit, onProposer: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.unknown_title, affiches),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedButton(onClick = onExporter, enabled = total > 0) {
                Text(stringResource(Res.string.unknown_export))
            }
            onProposer?.let { proposer ->
                Button(onClick = proposer) { Text(stringResource(Res.string.unknown_propose)) }
            }
        }
        TexteSecondaire(stringResource(Res.string.unknown_hint), petit = true)
        LegendeOrigines()
    }
}

@Composable
private fun LigneInconnu(inconnu: PaquetInconnu) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 16.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // La colonne des cases reste vide : il n'y a rien à cocher ici.
        Spacer(Modifier.width(COLONNE_CASES))
        IconeOrigine(inconnu.origine)
        Text(
            text = inconnu.paquet,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (inconnu.etat == EtatPaquet.DESACTIVE) {
            TexteSecondaire(stringResource(Res.string.state_disabled), petit = true)
        }
    }
}
