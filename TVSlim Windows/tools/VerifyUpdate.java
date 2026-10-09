import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;

/**
 * Checks that a TV Slim installer was signed with the project key. This is the check the app runs before
 * installing an update, so anyone can repeat it by hand.
 *
 * <pre>
 *   java tools/VerifyUpdate.java TVSlim-Windows-1.2.3.msi 1.2.3 [gradle.properties]
 * </pre>
 *
 * Reads the signature next to the installer (.msi.sig) and the public key from gradle.properties
 * (updatesPublicKey), the same key the app embeds. Exits 0 if the signature is valid, 1 otherwise.
 *
 * The release workflow runs it right after signing: a private key that does not match the embedded key
 * would produce a release that every install refuses.
 *
 * The message format is the one in SignerMiseAJour.java and VerificationSignature.kt; a test checks that
 * all three agree. No dependencies: JDK 17 or later.
 */
public class VerifyUpdate {

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2 || arguments.length > 3) {
            System.err.println("usage : java VerifierMiseAJour.java <installateur.msi> <version> [gradle.properties]");
            System.exit(2);
        }
        Path installer = Path.of(arguments[0]);
        String version = arguments[1];
        Path properties = Path.of(arguments.length == 3 ? arguments[2] : "gradle.properties");

        Properties fetched = new Properties();
        try (var stream = Files.newInputStream(properties)) {
            fetched.load(stream);
        }
        String key = fetched.getProperty("updatesPublicKey", "").trim();
        if (key.isEmpty()) {
            System.err.println("updatesPublicKey est absente de " + properties);
            System.exit(2);
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(installer)) {
            byte[] buffer = new byte[64 * 1024];
            int justRead;
            while ((justRead = stream.read(buffer)) >= 0) {
                digest.update(buffer, 0, justRead);
            }
        }
        String fingerprint = HexFormat.of().formatHex(digest.digest());
        byte[] message = ("TVSlim-Windows\n" + version + "\n" + fingerprint).getBytes(StandardCharsets.UTF_8);

        Path signatureFile = installer.resolveSibling(installer.getFileName() + ".sig");
        boolean valid;
        try {
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key)));
            Signature verification = Signature.getInstance("Ed25519");
            verification.initVerify(publicKey);
            verification.update(message);
            valid = verification.verify(Base64.getDecoder().decode(Files.readString(signatureFile).trim()));
        } catch (java.io.IOException | IllegalArgumentException | java.security.GeneralSecurityException error) {
            System.err.println(error);
            valid = false;
        }

        System.out.println((valid ? "Signature valide" : "SIGNATURE INVALIDE") + " : "
                + installer.getFileName() + "  sha256=" + fingerprint);
        System.exit(valid ? 0 : 1);
    }
}
