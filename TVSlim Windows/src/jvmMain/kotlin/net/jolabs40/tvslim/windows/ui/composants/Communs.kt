package net.jolabs40.tvslim.windows.ui.composants

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.catalog.Risque
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.baseline_connected_tv_24
import net.jolabs40.tvslim.windows.ressources.risk_high
import net.jolabs40.tvslim.windows.ressources.risk_low
import net.jolabs40.tvslim.windows.ressources.risk_medium
import net.jolabs40.tvslim.windows.ressources.risk_none
import net.jolabs40.tvslim.windows.ui.MessageUi
import net.jolabs40.tvslim.windows.ui.rediger
import net.jolabs40.tvslim.windows.ui.theme.CouleursRisque
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Une carte titrée : la brique de presque tous les écrans, comme sur le téléphone. */
@Composable
fun CarteSection(
    titre: String,
    modifier: Modifier = Modifier,
    couleurs: CardColors = CardDefaults.cardColors(),
    espacement: Dp = 8.dp,
    contenu: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth(), colors = couleurs) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(espacement),
        ) {
            Text(
                text = titre,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            contenu()
        }
    }
}

/** Un libellé à gauche, sa valeur à droite. */
@Composable
fun LigneValeur(libelle: String, valeur: String) {
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
fun TexteSecondaire(texte: String, modifier: Modifier = Modifier, petit: Boolean = false) {
    Text(
        text = texte,
        modifier = modifier,
        style = if (petit) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Une barre proportionnelle : plus parlante qu'un nombre isolé. */
@Composable
fun Jauge(valeur: Long, total: Long, modifier: Modifier = Modifier) {
    val fraction = if (total > 0) (valeur.toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
    }
}

/** Ce qu'affiche un onglet qui n'a rien à montrer sans téléviseur. */
@Composable
fun EcranVide(texte: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(Res.drawable.baseline_connected_tv_24),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = texte,
                modifier = Modifier.widthIn(max = 420.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Deux colonnes qui défilent chacune de leur côté — un bureau a la largeur que le téléphone n'a
 * pas. Une fenêtre étroite les empile, dans l'ordre du téléphone.
 */
@Composable
fun DeuxColonnes(
    gauche: @Composable ColumnScope.() -> Unit,
    droite: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth < 760.dp) {
            Row(modifier = Modifier.fillMaxSize()) {
                ColonneDefilante(poids = 1f) {
                    gauche()
                    droite()
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                ColonneDefilante(poids = 1f, contenu = gauche)
                VerticalDivider()
                ColonneDefilante(poids = 1f, contenu = droite)
            }
        }
    }
}

@Composable
private fun RowScope.ColonneDefilante(poids: Float, contenu: @Composable ColumnScope.() -> Unit) {
    val defilement = rememberScrollState()
    Box(modifier = Modifier.weight(poids).fillMaxHeight()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(defilement).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = contenu,
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(defilement),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
fun PastilleRisque(risque: Risque, taille: Dp = 9.dp) {
    Box(modifier = Modifier.size(taille).background(couleurRisque(risque), CircleShape))
}

fun couleurRisque(risque: Risque): Color = when (risque) {
    Risque.AUCUN -> CouleursRisque.aucun
    Risque.FAIBLE -> CouleursRisque.faible
    Risque.MOYEN -> CouleursRisque.moyen
    Risque.ELEVE -> CouleursRisque.eleve
}

@Composable
fun libelleRisque(risque: Risque): String = stringResource(
    when (risque) {
        Risque.AUCUN -> Res.string.risk_none
        Risque.FAIBLE -> Res.string.risk_low
        Risque.MOYEN -> Res.string.risk_medium
        Risque.ELEVE -> Res.string.risk_high
    },
)

/** Rédige un [MessageUi] dans la langue de la personne, pour l'afficher hors de la bannière. */
@Composable
fun texteDe(message: MessageUi): String {
    val texte by produceState(initialValue = "", message) { value = message.rediger() }
    return texte
}
