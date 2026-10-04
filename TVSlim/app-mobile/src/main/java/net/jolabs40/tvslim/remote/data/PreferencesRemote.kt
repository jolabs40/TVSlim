package net.jolabs40.tvslim.remote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import net.jolabs40.tvslim.remote.adb.PORT_ADB_PAR_DEFAUT
import net.jolabs40.tvslim.soutien.MagasinSoutien
import net.jolabs40.tvslim.soutien.MemoireSoutien
import javax.inject.Inject
import javax.inject.Singleton

private val Context.magasin: DataStore<Preferences> by preferencesDataStore(name = "tvslim-remote")

/** Dernier téléviseur joint, pour ne pas ressaisir son adresse à chaque fois — et où en est le bandeau de soutien. */
@Singleton
class PreferencesRemote @Inject constructor(
    @ApplicationContext private val contexte: Context,
) : MagasinSoutien {

    suspend fun dernierHote(): String = contexte.magasin.data.first()[CLE_HOTE].orEmpty()

    suspend fun dernierPort(): Int =
        contexte.magasin.data.first()[CLE_PORT] ?: PORT_ADB_PAR_DEFAUT

    /**
     * Retient comment s'appelle l'appareil à cette adresse. Le service ADB ne publie qu'un
     * numéro de série ; une fois connecté une première fois, on connaît son modèle, autant
     * s'en servir pour les fois suivantes.
     */
    suspend fun retenirNom(hote: String, nom: String) {
        if (hote.isBlank() || nom.isBlank()) return
        contexte.magasin.edit { it[stringPreferencesKey(PREFIXE_NOM + hote)] = nom }
    }

    suspend fun nomsConnus(): Map<String, String> = contexte.magasin.data.first()
        .asMap()
        .filterKeys { it.name.startsWith(PREFIXE_NOM) }
        .map { (cle, valeur) -> cle.name.removePrefix(PREFIXE_NOM) to valeur.toString() }
        .toMap()

    suspend fun retenir(hote: String, port: Int) {
        contexte.magasin.edit {
            it[CLE_HOTE] = hote
            it[CLE_PORT] = port
        }
    }

    override suspend fun lireSoutien(): MemoireSoutien = contexte.magasin.data.first().let {
        MemoireSoutien(donDeclare = it[CLE_DON] ?: false, derniereInvitation = it[CLE_INVITATION] ?: 0L)
    }

    override suspend fun ecrireSoutien(memoire: MemoireSoutien) {
        contexte.magasin.edit {
            it[CLE_DON] = memoire.donDeclare
            it[CLE_INVITATION] = memoire.derniereInvitation
        }
    }

    private companion object {
        const val PREFIXE_NOM = "nom_"
        val CLE_HOTE = stringPreferencesKey("dernier_hote")
        val CLE_PORT = intPreferencesKey("dernier_port")
        val CLE_DON = booleanPreferencesKey("soutien_don_declare")
        val CLE_INVITATION = longPreferencesKey("soutien_derniere_invitation")
    }
}
