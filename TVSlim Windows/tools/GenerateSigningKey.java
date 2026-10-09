import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Generates the Ed25519 key pair that signs TV Slim updates.
 *
 * <pre>
 *   java tools/GenerateSigningKey.java cle-publique.txt > cle-privee.json
 * </pre>
 *
 * The public key is written to the given file and copied into gradle.properties (updatesPublicKey).
 * The private key goes to stdout as JSON ({"prive": "..."}) so it can go straight into a vault or the
 * TVSLIM_CLE_SIGNATURE GitHub secret without being displayed.
 *
 * Run once per release line: changing the key leaves every installed version unable to verify the next
 * ones. A fork that publishes its own builds generates its own key and replaces the public key first.
 */
public class GenerateSigningKey {

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            System.err.println("usage : java GenererCleSignature.java <fichier-cle-publique>");
            System.exit(2);
        }
        Path publicKey = Path.of(arguments[0]);
        if (Files.exists(publicKey)) {
            System.err.println(publicKey + " existe déjà : rien n'a été généré.");
            System.exit(2);
        }

        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(publicKey, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n");
        System.out.print("{\"prive\":\"" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\"}");
    }
}
