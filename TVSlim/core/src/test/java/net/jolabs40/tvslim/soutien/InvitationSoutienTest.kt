package net.jolabs40.tvslim.soutien

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.jolabs40.tvslim.fichiers.EchecDepot
import net.jolabs40.tvslim.fichiers.ResultatDepot
import net.jolabs40.tvslim.fichiers.SensTransfert
import net.jolabs40.tvslim.fichiers.SignalFichiers
import net.jolabs40.tvslim.installation.ApkChoisi
import net.jolabs40.tvslim.installation.CauseEchec
import net.jolabs40.tvslim.installation.ManifesteApk
import net.jolabs40.tvslim.installation.ResultatInstallation
import net.jolabs40.tvslim.moteur.ResultatAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Quand l'invitation à soutenir TV Slim se montre, et ce qui la mérite. Les deux applications s'en remettent à
 * ces règles : une invitation qui reviendrait chaque jour, ou après un don déclaré, ferait plus de tort que de bien.
 */
class InvitationSoutienTest {

    private val jour = 24L * 60 * 60 * 1000
    private val depart = 1_790_000_000_000L

    @Test
    fun `la premiere invitation se montre, la suivante attend trente jours`() {
        assertTrue(InvitationSoutien.aProposer(MemoireSoutien(), depart))

        val montree = MemoireSoutien(derniereInvitation = depart)
        assertFalse(InvitationSoutien.aProposer(montree, depart + 29 * jour))
        assertTrue(InvitationSoutien.aProposer(montree, depart + 30 * jour))
    }

    @Test
    fun `un don declare tait l'invitation pour toujours`() {
        val donne = MemoireSoutien(donDeclare = true)

        assertFalse(InvitationSoutien.aProposer(donne, depart))
        assertFalse(InvitationSoutien.aProposer(donne.copy(derniereInvitation = depart), depart + 400 * jour))
    }

    @Test
    fun `une horloge revenue en arriere ne bloque pas l'invitation`() {
        assertTrue(InvitationSoutien.aProposer(MemoireSoutien(derniereInvitation = depart), depart - jour))
    }

    @Test
    fun `seul un lot d'actions toutes abouties merite un merci`() {
        val ok = ResultatAction("com.tcl.ad", "Publicité", reussi = true)
        val ko = ResultatAction("com.tcl.x", "Refusé", reussi = false, message = "protégé")

        assertTrue(InvitationSoutien.merite(listOf(ok, ok.copy(paquet = "com.tcl.b"))))
        assertFalse(InvitationSoutien.merite(listOf(ok, ko)))
        assertFalse(InvitationSoutien.merite(emptyList<ResultatAction>()))
    }

    @Test
    fun `un transfert merite un merci s'il est arrive au bout, dans un sens comme dans l'autre`() {
        val envoi = ResultatDepot(destination = "/sdcard/Download", envoyes = 3, nombre = 3)

        assertTrue(InvitationSoutien.merite(envoi))
        assertTrue(InvitationSoutien.merite(envoi.copy(sens = SensTransfert.RECEPTION)))
        assertFalse(InvitationSoutien.merite(envoi.copy(annule = true)))
        assertFalse(InvitationSoutien.merite(envoi.copy(interrompu = true)))
        assertFalse(InvitationSoutien.merite(envoi.copy(envoyes = 2, echecs = listOf(EchecDepot("a.txt", "refusé")))))
        assertFalse(InvitationSoutien.merite(envoi.copy(envoyes = 0, nombre = 0)))

        assertTrue(InvitationSoutien.merite(SignalFichiers.Depot(envoi)))
        assertFalse(InvitationSoutien.merite(SignalFichiers.Occupe))
    }

    @Test
    fun `seule une installation reussie merite un merci`() {
        val apk = ApkChoisi(
            fichier = File("HippieTV.apk"),
            nom = "HippieTV.apk",
            taille = 1L,
            manifeste = ManifesteApk("net.jolabs40.hippietv", 1, "1.0", 26),
            installee = null,
        )

        assertTrue(InvitationSoutien.merite(ResultatInstallation.Reussie(apk)))
        assertFalse(InvitationSoutien.merite(ResultatInstallation.Echouee(apk, CauseEchec.SIGNATURE_DIFFERENTE, "")))
    }

    @Test
    fun `le bandeau se montre, se date, et ne revient pas avant son heure`() {
        val magasin = Magasin()
        var maintenant = depart
        val pilote = PiloteSoutien(magasin, CoroutineScope(Dispatchers.Unconfined)) { maintenant }

        pilote.remercier()
        assertTrue(pilote.visible.value)
        assertEquals(depart, magasin.memoire.derniereInvitation)

        pilote.ecarter()
        maintenant += 10 * jour
        pilote.remercier()
        assertFalse(pilote.visible.value)

        maintenant += 20 * jour
        pilote.remercier()
        assertTrue(pilote.visible.value)
    }

    @Test
    fun `apres un don declare, le bandeau ne revient plus`() {
        val magasin = Magasin()
        var maintenant = depart
        val pilote = PiloteSoutien(magasin, CoroutineScope(Dispatchers.Unconfined)) { maintenant }

        pilote.remercier()
        pilote.declarerDon()
        assertFalse(pilote.visible.value)
        assertTrue(magasin.memoire.donDeclare)

        maintenant += 400 * jour
        pilote.remercier()
        assertFalse(pilote.visible.value)
    }

    private class Magasin(var memoire: MemoireSoutien = MemoireSoutien()) : MagasinSoutien {
        override suspend fun lireSoutien(): MemoireSoutien = memoire

        override suspend fun ecrireSoutien(memoire: MemoireSoutien) {
            this.memoire = memoire
        }
    }
}
