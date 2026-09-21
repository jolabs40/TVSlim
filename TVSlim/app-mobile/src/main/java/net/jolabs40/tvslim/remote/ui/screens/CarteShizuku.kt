package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.commande.EchangeCommande
import net.jolabs40.tvslim.commande.RelanceShizuku
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.ActionsShizuku
import net.jolabs40.tvslim.remote.ui.EtatShizuku

/**
 * Relance le service Shizuku du téléviseur, qui meurt à chaque extinction.
 *
 * ⚠️ **Shizuku n'est pas le canal de TV Slim** — le § 1 l'a écarté au profit du compagnon ADB,
 * dont l'autorisation survit aux redémarrages. Cette carte rend un service aux **autres**
 * applications du téléviseur, celles qui en dépendent et qu'aucun geste sur place ne peut
 * réveiller : le service tient les privilèges du shell, donc rien de ce qui tourne sur le
 * téléviseur ne saurait le relancer.
 *
 * ⚠️ **Un bouton, aucune saisie.** La chaîne est une constante du noyau : contrairement à la
 * carte « Commande ADB », il n'y a rien à taper ici, donc rien à contrôler. La sortie brute
 * reste à l'écran — c'est elle qui donne le pid, et un refus mérite d'être relu.
 */
@Composable
fun CarteShizuku(etat: EtatShizuku, actions: ActionsShizuku) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.shizuku_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.shizuku_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = actions.onRelancer, enabled = !etat.enCours) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.shizuku_restart))
                }
                if (etat.enCours) CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
            etat.derniere?.let { Bilan(it) }
        }
    }
}

/**
 * Ce que la relance a rendu.
 *
 * ⚠️ **Le code de retour ne suffit pas à dire que c'est parti.** Le starter rend 0 même quand il
 * renonce ; c'est sa sortie qui annonce le pid ([RelanceShizuku.demarre]). Un `0` muet vaut donc
 * un échec, et le dire autrement enverrait chercher la panne dans l'application qui attend
 * Shizuku.
 */
@Composable
private fun Bilan(echange: EchangeCommande) {
    val reussi = echange.reussie && RelanceShizuku.demarre(echange.sortie)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (reussi) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (reussi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(if (reussi) R.string.shizuku_started else R.string.shizuku_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = if (reussi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
    if (echange.sortie.isNotBlank()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            SelectionContainer {
                Text(
                    text = echange.sortie,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(12.dp)
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
