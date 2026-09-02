package net.jolabs40.tvslim.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

/** Élément de liste focusable : toute la ligne est la cible du D-pad. */
@Composable
fun LigneFocusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contenu: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(14.dp), contentAlignment = Alignment.CenterStart) {
            contenu()
        }
    }
}

/** Bandeau de retour, affiché après chaque action. */
@Composable
fun Bandeau(message: String, onFermer: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onFermer, modifier = modifier.fillMaxWidth()) {
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
