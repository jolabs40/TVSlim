package net.jolabs40.tvslim.remote.fichiers

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import net.jolabs40.tvslim.fichiers.FichierLocal
import net.jolabs40.tvslim.fichiers.LotLocal
import java.io.IOException
import java.io.InputStream

/** Un document du sélecteur d'Android, lu par son fournisseur au moment de l'envoi : rien n'est copié avant. */
private class FichierDocument(
    private val resolveur: ContentResolver,
    private val uri: Uri,
    override val chemin: String,
    override val taille: Long,
    override val date: Long,
) : FichierLocal {
    override fun ouvrir(): InputStream = resolveur.openInputStream(uri) ?: throw IOException("Document illisible : $chemin")
}

/**
 * Des documents choisis un à un. Deux d'entre eux peuvent porter le même nom, venus de dossiers différents :
 * le second prend « (2) », sans quoi il remplacerait le premier sur le téléviseur.
 */
fun lotDeDocuments(contexte: Context, documents: List<Uri>): LotLocal {
    val resolveur = contexte.contentResolver
    val pris = mutableSetOf<String>()
    val fichiers = documents.distinct().map { uri ->
        var nom = "document"
        var taille = 0L
        var date = 0L
        // Toutes les colonnes : un fournisseur qui ignore la date de modification refuserait qu'on la demande.
        resolveur.query(uri, null, null, null, null)?.use { curseur ->
            if (curseur.moveToFirst()) {
                curseur.texte(OpenableColumns.DISPLAY_NAME)?.let { nom = it }
                taille = curseur.nombre(OpenableColumns.SIZE)
                date = curseur.nombre(Document.COLUMN_LAST_MODIFIED)
            }
        }
        FichierDocument(resolveur, uri, nomLibre(nom, pris), taille, date)
    }
    return LotLocal(fichiers)
}

/**
 * Un dossier choisi dans le sélecteur, parcouru par le fournisseur de documents — les fichiers avec leur chemin
 * relatif, chaque dossier traversé, vides compris.
 */
fun lotDeDossier(contexte: Context, arbre: Uri): LotLocal {
    val resolveur = contexte.contentResolver
    val racine = DocumentsContract.getTreeDocumentId(arbre)
    val nom = resolveur.query(DocumentsContract.buildDocumentUriUsingTree(arbre, racine), null, null, null, null)
        ?.use { curseur -> if (curseur.moveToFirst()) curseur.texte(Document.COLUMN_DISPLAY_NAME) else null }
        ?: racine.substringAfterLast(':').substringAfterLast('/').ifBlank { "dossier" }
    val fichiers = mutableListOf<FichierLocal>()
    val dossiers = mutableListOf<String>()
    parcourir(resolveur, arbre, racine, nom, fichiers, dossiers, profondeur = 0)
    return LotLocal(fichiers, dossiers)
}

private fun parcourir(
    resolveur: ContentResolver,
    arbre: Uri,
    document: String,
    chemin: String,
    fichiers: MutableList<FichierLocal>,
    dossiers: MutableList<String>,
    profondeur: Int,
) {
    dossiers += chemin
    if (profondeur >= PROFONDEUR_MAX) return
    val sousDossiers = mutableListOf<Pair<String, String>>()
    val colonnes = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
    )
    resolveur.query(DocumentsContract.buildChildDocumentsUriUsingTree(arbre, document), colonnes, null, null, null)
        ?.use { curseur ->
            while (curseur.moveToNext()) {
                val id = curseur.getString(0) ?: continue
                val nom = curseur.getString(1) ?: continue
                val sousChemin = "$chemin/$nom"
                if (curseur.getString(2) == Document.MIME_TYPE_DIR) {
                    sousDossiers += id to sousChemin
                } else {
                    fichiers += FichierDocument(
                        resolveur = resolveur,
                        uri = DocumentsContract.buildDocumentUriUsingTree(arbre, id),
                        chemin = sousChemin,
                        taille = if (curseur.isNull(3)) 0L else curseur.getLong(3),
                        date = if (curseur.isNull(4)) 0L else curseur.getLong(4),
                    )
                }
            }
        }
    // Le curseur refermé d'abord : un arbre profond en garderait sinon un ouvert par niveau.
    sousDossiers.sortedBy { it.second.lowercase() }.forEach { (id, sousChemin) ->
        parcourir(resolveur, arbre, id, sousChemin, fichiers, dossiers, profondeur + 1)
    }
}

private fun nomLibre(nom: String, pris: MutableSet<String>): String {
    var libre = nom
    var rang = 2
    while (!pris.add(libre)) {
        val point = nom.lastIndexOf('.').takeIf { it > 0 } ?: nom.length
        libre = "${nom.substring(0, point)} ($rang)${nom.substring(point)}"
        rang++
    }
    return libre
}

private fun Cursor.texte(colonne: String): String? =
    getColumnIndex(colonne).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

private fun Cursor.nombre(colonne: String): Long =
    getColumnIndex(colonne).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: 0L

/** Une garde, au cas où un fournisseur exposerait un arbre qui se reboucle. */
private const val PROFONDEUR_MAX = 64
