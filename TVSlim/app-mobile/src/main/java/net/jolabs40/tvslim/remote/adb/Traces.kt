package net.jolabs40.tvslim.remote.adb

import net.jolabs40.tvslim.remote.BuildConfig

/**
 * Le **détail** d'une trace ne part qu'en debug ; l'événement, lui, reste journalisé.
 *
 * Savoir qu'une commande a échoué garde son intérêt en release — c'est ce qu'on cherche
 * quand une connexion refuse de s'ouvrir. Savoir *laquelle*, beaucoup moins : ce détail
 * porte l'adresse du téléviseur, la liste des paquets qu'on y désactive et les permissions
 * qu'on y accorde. Logcat n'a pas à tenir l'inventaire d'un téléviseur.
 *
 * Rien de tout cela n'est un secret, et depuis Android 11 aucune application tierce ne lit
 * logcat sans `READ_LOGS`. C'est de l'hygiène plutôt qu'une faille — mais la règle du projet
 * est de ne rien laisser filer en release, et elle ne coûte rien à tenir.
 */
internal fun detail(texte: String): String = if (BuildConfig.DEBUG) " : $texte" else ""
