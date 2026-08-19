package io.cassyx.licensing.mint;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Generates the Ed25519 key pair the whole licensing scheme rests on.
 *
 * <pre>{@code
 * java -cp cassyx-licensing.jar -Dloader.main=io.cassyx.licensing.mint.KeyPairTool \
 *      org.springframework.boot.loader.launch.PropertiesLauncher
 * # or, from a checkout:
 * mvn -q -pl licensing exec:java -Dexec.mainClass=io.cassyx.licensing.mint.KeyPairTool
 * }</pre>
 *
 * <p>It prints which half goes where, because getting that backwards is catastrophic and silent:
 * the product would still boot, and only the private key would have been published to every
 * customer.
 */
public final class KeyPairTool {

  private KeyPairTool() {}

  public static void main(String[] args) throws Exception {
    KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    Base64.Encoder encoder = Base64.getEncoder();
    String publicKey = encoder.encodeToString(pair.getPublic().getEncoded());
    String privateKey = encoder.encodeToString(pair.getPrivate().getEncoded());

    System.out.println("# ---------------------------------------------------------------");
    System.out.println("# PUBLIC half - SHIPS with the product. Safe to publish.");
    System.out.println("# Put this in the product's environment:");
    System.out.println("CASSYX_LICENSE_PUBLIC_KEY=" + publicKey);
    System.out.println();
    System.out.println("# PRIVATE half - NEVER ships, never enters the product image, never the");
    System.out.println("# repository. Only this licensing service sees it. Anyone holding it can");
    System.out.println("# mint licences for free, forever.");
    System.out.println("CASSYX_LICENSING_PRIVATE_KEY=" + privateKey);
    System.out.println("# ---------------------------------------------------------------");
  }
}
