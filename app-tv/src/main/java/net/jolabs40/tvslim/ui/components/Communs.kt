package net.jolabs40.tvslim.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import net.jolabs40.tvslim.catalog.Risque
import net.jolabs40.tvslim.ui.theme.CouleursRisque

/** Titre d'écran, avec sa ligne d'explication. */
@Composable
fun EnTete(titre: String, sousTitre: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(bottom = 16.dp)) {
        Text(
            text = titre,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (sousTitre != null) {
            Text(
                text = sousTitre,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Bloc d'information : un simple fond, jamais une cible du D-pad. */
@Composable
fun Bloc(
    modifier: Modifier = Modifier,
    contenu: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        content = contenu,
    )
}

/** Ligne « libellé : valeur » d'un bloc de mesures. */
@Composable
fun LigneMesure(libelle: String, valeur: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
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

/** Pastille colorée résumant le risque d'une désactivation. */
@Composable
fun PastilleRisque(risque: Risque, modifier: Modifier = Modifier) {
    val couleur = when (risque) {
        Risque.AUCUN -> CouleursRisque.aucun
        Risque.FAIBLE -> CouleursRisque.faible
        Risque.MOYEN -> CouleursRisque.moyen
        Risque.ELEVE -> CouleursRisque.eleve
    }
    Box(modifier = modifier.size(10.dp).background(couleur, CircleShape))
}

/**
 * Élément de liste focusable : toute la ligne est la cible du D-pad.
 *
 * Le grossissement par défaut de Compose for TV (1,1×) fait sauter les lignes voisines à chaque
 * déplacement, ce qui est fatigant sur une longue liste. Il est ramené à un frémissement : le
 * fond clair de la ligne focalisée suffit largement à la désigner.
 */
@Composable
fun LigneFocusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contenu: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Box(modifier = Modifier.padding(14.dp), contentAlignment = Alignment.CenterStart) {
            contenu()
        }
    }
}

/**
 * Repère de position dans une longue liste : « 12 sur 56 ».
 *
 * Un téléviseur n'a pas de curseur à traîner ; sans ce repère, on ignore où l'on est et combien
 * il reste.
 */
@Composable
fun CompteurListe(etat: LazyListState, total: Int, modifier: Modifier = Modifier) {
    if (total <= 0) return
    val courant = (etat.firstVisibleItemIndex + 1).coerceAtMost(total)
    Text(
        text = "$courant / $total",
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Barre de défilement décorative, calée sur l'état de la liste. Elle n'est pas manipulable —
 * à la télécommande, elle sert uniquement à situer d'un coup d'œil.
 */
@Composable
fun BarreDefilement(etat: LazyListState, modifier: Modifier = Modifier) {
    val info = etat.layoutInfo
    val total = info.totalItemsCount
    val visibles = info.visibleItemsInfo.size
    if (total == 0 || visibles == 0 || total <= visibles) return

    val proportion = (visibles.toFloat() / total).coerceIn(FRACTION_MIN, 1f)
    val avancement = etat.firstVisibleItemIndex.toFloat() / (total - visibles).coerceAtLeast(1)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
    ) {
        val hauteurCurseur = maxHeight * proportion
        Box(
            modifier = Modifier
                .offset(y = (maxHeight - hauteurCurseur) * avancement.coerceIn(0f, 1f))
                .height(hauteurCurseur)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
        )
    }
}

/** Bandeau de retour, affiché après chaque action. */
@Composable
fun Bandeau(message: String, onFermer: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onFermer,
        modifier = modifier.fillMaxWidth(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Text(
                text = message,
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** En dessous, le curseur deviendrait un trait invisible sur une liste très longue. */
private const val FRACTION_MIN = 0.08f

