import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Crée la paire de clés Ed25519 qui signe les mises à jour de TV Slim.
 *
 * <pre>
 *   java outils/GenererCleSignature.java cle-publique.txt > cle-privee.json
 * </pre>
 *
 * La clé publique n'a rien de secret : elle est écrite dans le fichier indiqué, et se recopie dans
 * gradle.properties (clePubliqueMisesAJour). La clé privée sort sur la sortie standard, en JSON
 * ({"prive": "…"}), pour aller directement dans un coffre ou dans le secret GitHub
 * TVSLIM_CLE_SIGNATURE sans s'afficher nulle part.
 *
 * À ne lancer qu'une fois par lignée de publication : changer de clé rend toutes les versions déjà
 * installées incapables de vérifier les suivantes. Une version publiée ailleurs (fork) génère la
 * sienne, et remplace la clé publique avant sa première publication.
 */
public class GenererCleSignature {

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            System.err.println("usage : java GenererCleSignature.java <fichier-cle-publique>");
            System.exit(2);
        }
        Path publique = Path.of(arguments[0]);
        if (Files.exists(publique)) {
            System.err.println(publique + " existe déjà : rien n'a été généré.");
            System.exit(2);
        }

        KeyPair paire = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(publique, Base64.getEncoder().encodeToString(paire.getPublic().getEncoded()) + "\n");
        System.out.print("{\"prive\":\"" + Base64.getEncoder().encodeToString(paire.getPrivate().getEncoded()) + "\"}");
    }
}
