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
 * When the support invitation shows, and what earns it. Both apps rely on these rules: it must never come back
 * daily, nor after a declared donation.
 */
class InvitationSoutienTest {

    private val jour = 24L * 60 * 60 * 1000
    private val depart = 1_790_000_000_000L

    @Test
    fun `the first invitation shows, the next one waits thirty days`() {
        assertTrue(InvitationSoutien.aProposer(MemoireSoutien(), depart))

        val montree = MemoireSoutien(derniereInvitation = depart)
        assertFalse(InvitationSoutien.aProposer(montree, depart + 29 * jour))
        assertTrue(InvitationSoutien.aProposer(montree, depart + 30 * jour))
    }

    @Test
    fun `a declared donation silences the invitation for good`() {
        val donne = MemoireSoutien(donDeclare = true)

        assertFalse(InvitationSoutien.aProposer(donne, depart))
        assertFalse(InvitationSoutien.aProposer(donne.copy(derniereInvitation = depart), depart + 400 * jour))
    }

    @Test
    fun `a clock set backwards does not block the invitation`() {
        assertTrue(InvitationSoutien.aProposer(MemoireSoutien(derniereInvitation = depart), depart - jour))
    }

    @Test
    fun `only a batch where every action succeeded earns a thank you`() {
        val ok = ResultatAction("com.tcl.ad", "Publicité", reussi = true)
        val ko = ResultatAction("com.tcl.x", "Refusé", reussi = false, message = "protégé")

        assertTrue(InvitationSoutien.merite(listOf(ok, ok.copy(paquet = "com.tcl.b"))))
        assertFalse(InvitationSoutien.merite(listOf(ok, ko)))
        assertFalse(InvitationSoutien.merite(emptyList<ResultatAction>()))
    }

    @Test
    fun `a transfer earns a thank you if it completed, in either direction`() {
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
    fun `only a successful install earns a thank you`() {
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
    fun `the banner shows, records its date, and does not return before its time`() {
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
    fun `after a declared donation, the banner never returns`() {
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
