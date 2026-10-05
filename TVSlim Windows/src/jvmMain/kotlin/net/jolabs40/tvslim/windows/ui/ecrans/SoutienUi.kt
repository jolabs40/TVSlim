package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.about_support
import net.jolabs40.tvslim.windows.ressources.kofi_symbol
import net.jolabs40.tvslim.windows.ressources.support_already
import net.jolabs40.tvslim.windows.ressources.support_donate
import net.jolabs40.tvslim.windows.ressources.support_later
import net.jolabs40.tvslim.windows.ressources.support_text
import net.jolabs40.tvslim.windows.ressources.support_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Le symbole de Ko-fi — la tasse au cœur —, tel que Ko-fi le publie dans ses ressources de marque, sans teinte : il se
 * reconnaît à ses couleurs, et son intérieur blanc le garde lisible sur le thème sombre. Plus large que haut, il tient
 * dans la boîte de 24 dp d'une icône. Fichiers ramenés à 24 dp de large dans chaque `drawable-*dpi`.
 */
@Composable
fun SymboleKofi(contentDescription: String?, modifier: Modifier = Modifier, taille: Dp = 24.dp) {
    Image(
        painter = painterResource(Res.drawable.kofi_symbol),
        contentDescription = contentDescription,
        modifier = modifier.size(taille),
    )
}

/**
 * Le symbole de Ko-fi dans la barre du haut, visible depuis chaque onglet, qui ouvre la page de soutien. Discret par principe : rien ne
 * clignote, rien ne revient seul — c'est le bandeau qui remercie après un service rendu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BoutonSoutien(onClick: () -> Unit) {
    val libelle = stringResource(Res.string.about_support)
    TooltipArea(
        tooltip = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = libelle,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        },
    ) {
        IconButton(onClick = onClick) {
            SymboleKofi(contentDescription = libelle)
        }
    }
}

/**
 * Le bandeau de soutien, sous celui des mises à jour : il se montre après un service rendu, et seulement quand
 * `PiloteSoutien` le décide. Il ne bloque rien, et chacun de ses trois boutons le referme.
 */
@Composable
fun BanniereSoutien(
    visible: Boolean,
    onSoutenir: () -> Unit,
    onDejaFait: () -> Unit,
    onPlusTard: () -> Unit,
) {
    if (!visible) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SymboleKofi(contentDescription = null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(Res.string.support_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = stringResource(Res.string.support_text), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onDejaFait) { Text(stringResource(Res.string.support_already)) }
            TextButton(onClick = onPlusTard) { Text(stringResource(Res.string.support_later)) }
            Button(onClick = onSoutenir) {
                SymboleKofi(contentDescription = null, taille = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.support_donate))
            }
        }
    }
}
