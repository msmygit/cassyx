package io.cassyx.core.impl;

import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.SshTunnel;
import io.cassyx.core.api.SshTunnelSpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local port forward over SSH, established before the {@code CqlSession} is built
 * (plan section 3, "SSH tunnel support - local port forward before session build").
 *
 * <p>Apache MINA sshd rather than JSch: JSch upstream is abandoned, and MINA ships both a client and
 * an embeddable server, which is what lets the tunnel be covered by a real test instead of a mock.
 *
 * <p>Security choices that are deliberate:
 *
 * <ul>
 *   <li><b>Host keys are verified against a pinned entry</b> when {@code strictHostKeyChecking} is
 *       on. An SSH tunnel that accepts any host key protects nothing: an attacker who can answer on
 *       the bastion's address sees the cluster credentials in the clear.
 *   <li><b>The forward binds to loopback only.</b> Binding to {@code 0.0.0.0} would republish the
 *       customer's private cluster to everything that can reach the cassyx container.
 *   <li>No credential is logged. Failure messages name the host and the failure class, never the
 *       key or password.
 * </ul>
 */
public final class MinaSshTunnel implements SshTunnel {

  private static final Logger LOG = LoggerFactory.getLogger(MinaSshTunnel.class);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(20);

  private final SshClient client;
  private final ClientSession session;
  private final int localPort;
  private volatile boolean open = true;

  private MinaSshTunnel(SshClient client, ClientSession session, int localPort) {
    this.client = client;
    this.session = session;
    this.localPort = localPort;
  }

  /**
   * Connects, authenticates and starts the forward.
   *
   * @throws CassyxCoreException with an actionable message if any step fails
   */
  public static MinaSshTunnel open(SshTunnelSpec spec) {
    SshClient client = SshClient.setUpDefaultClient();
    configureHostKeyVerification(client, spec);
    client.start();

    ClientSession session = null;
    try {
      session =
          client
              .connect(spec.username(), spec.host(), spec.port())
              .verify(CONNECT_TIMEOUT)
              .getSession();

      if (spec.hasPrivateKey()) {
        for (KeyPair keyPair : loadKeyPairs(spec)) {
          session.addPublicKeyIdentity(keyPair);
        }
      }
      if (spec.hasPassword()) {
        session.addPasswordIdentity(spec.password().reveal());
      }
      session.auth().verify(AUTH_TIMEOUT);

      SshdSocketAddress bound =
          session.startLocalPortForwarding(
              new SshdSocketAddress("127.0.0.1", spec.localPort()),
              new SshdSocketAddress(spec.remoteHost(), spec.remotePort()));

      LOG.info(
          "SSH tunnel up: 127.0.0.1:{} -> {}:{} via {}@{}:{}",
          bound.getPort(),
          spec.remoteHost(),
          spec.remotePort(),
          spec.username(),
          spec.host(),
          spec.port());
      return new MinaSshTunnel(client, session, bound.getPort());
    } catch (IOException | RuntimeException e) {
      closeQuietly(session);
      client.stop();
      throw new CassyxCoreException(describeFailure(spec, e), e);
    }
  }

  private static String describeFailure(SshTunnelSpec spec, Exception e) {
    String reason = e.getClass().getSimpleName();
    String base =
        "Could not open the SSH tunnel to "
            + spec.username()
            + "@"
            + spec.host()
            + ":"
            + spec.port()
            + " ("
            + reason
            + ").";
    String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
    if (message.contains("authentication") || message.contains("no more authentication")) {
      return base
          + " The bastion rejected the credentials. Check the username, and that the private key "
          + "matches the one authorised on that host.";
    }
    if (message.contains("key") && message.contains("verif")) {
      return base
          + " The bastion presented a host key that does not match the pinned one. Either the key "
          + "rotated, or you are not talking to the host you think you are.";
    }
    return base;
  }

  /**
   * Loads a PEM private key. Visible for testing.
   *
   * @throws CassyxCoreException if the key cannot be parsed - never echoing the key material
   */
  static List<KeyPair> loadKeyPairs(SshTunnelSpec spec) {
    String pem = spec.privateKey().reveal();
    char[] passphrase = spec.privateKeyPassphrase().revealChars();
    try (InputStream in = new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8))) {
      Iterable<KeyPair> loaded =
          SecurityUtils.loadKeyPairIdentities(
              null,
              () -> "ssh-private-key",
              in,
              passphrase.length == 0 ? null : (s, r, i) -> new String(passphrase));
      List<KeyPair> keys = new ArrayList<>();
      if (loaded != null) {
        loaded.forEach(keys::add);
      }
      if (keys.isEmpty()) {
        throw new CassyxCoreException(
            "The SSH private key could not be read. Paste the full PEM document, including the "
                + "BEGIN and END lines.");
      }
      return keys;
    } catch (CassyxCoreException e) {
      throw e;
    } catch (IOException | GeneralSecurityException | RuntimeException e) {
      // RuntimeException too: MINA raises IllegalArgumentException from the base64 decoder for a
      // truncated key body, and a raw "Last unit does not have enough valid bits" is useless to
      // whoever pasted the key.
      throw new CassyxCoreException(
          "The SSH private key could not be read ("
              + e.getClass().getSimpleName()
              + "). If it is passphrase-protected, supply the passphrase.",
          e);
    }
  }

  private static void configureHostKeyVerification(SshClient client, SshTunnelSpec spec) {
    if (!spec.strictHostKeyChecking()) {
      // Explicitly opted out in the connection settings; the UI labels this as a downgrade.
      LOG.warn(
          "SSH host key checking is DISABLED for {}:{}. The tunnel is vulnerable to an active "
              + "man-in-the-middle; pin the bastion's host key to fix it.",
          spec.host(),
          spec.port());
      client.setServerKeyVerifier((session, address, key) -> true);
      return;
    }
    PublicKey pinned = parseHostKey(spec.knownHostsEntry());
    client.setServerKeyVerifier((session, address, key) -> keysMatch(pinned, key));
  }

  private static boolean keysMatch(PublicKey pinned, PublicKey presented) {
    if (pinned == null || presented == null) {
      return false;
    }
    // Compare the encoded form: PublicKey.equals is not contractually reliable across providers.
    return java.util.Arrays.equals(pinned.getEncoded(), presented.getEncoded());
  }

  /**
   * Parses a {@code known_hosts}-style line ({@code [host ]<type> <base64>[ comment]}).
   *
   * @throws CassyxCoreException if it cannot be parsed - failing closed, because falling back to
   *     "accept anything" here would silently remove the protection the user asked for
   */
  static PublicKey parseHostKey(String entry) {
    if (entry == null || entry.isBlank()) {
      throw new CassyxCoreException("No pinned SSH host key was supplied");
    }
    String[] fields = entry.trim().split("\\s+");
    for (int i = 0; i < fields.length - 1; i++) {
      if (fields[i].startsWith("ssh-") || fields[i].startsWith("ecdsa-")) {
        try {
          return PublicKeyEntry.parsePublicKeyEntry(fields[i] + " " + fields[i + 1])
              .resolvePublicKey(null, null, null);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
          throw new CassyxCoreException(
              "The pinned SSH host key could not be parsed. Paste one line from known_hosts, "
                  + "for example: ssh-ed25519 AAAAC3Nza...",
              e);
        }
      }
    }
    // Last resort: a bare "<type> <base64>" pair we did not recognise by prefix.
    if (fields.length >= 2 && isBase64(fields[fields.length - 1])) {
      try {
        return PublicKeyEntry.parsePublicKeyEntry(
                fields[fields.length - 2] + " " + fields[fields.length - 1])
            .resolvePublicKey(null, null, null);
      } catch (IOException | GeneralSecurityException | RuntimeException e) {
        throw new CassyxCoreException("The pinned SSH host key could not be parsed.", e);
      }
    }
    throw new CassyxCoreException(
        "The pinned SSH host key could not be parsed. Paste one line from known_hosts, for "
            + "example: ssh-ed25519 AAAAC3Nza...");
  }

  private static boolean isBase64(String value) {
    try {
      Base64.getDecoder().decode(value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  @Override
  public int localPort() {
    return localPort;
  }

  @Override
  public boolean isOpen() {
    return open && session.isOpen();
  }

  @Override
  public void close() {
    if (!open) {
      return;
    }
    open = false;
    closeQuietly(session);
    try {
      client.stop();
    } catch (RuntimeException e) {
      LOG.debug("Ignoring error while stopping the SSH client", e);
    }
  }

  private static void closeQuietly(ClientSession session) {
    if (session != null) {
      try {
        session.close(true);
      } catch (RuntimeException e) {
        LOG.debug("Ignoring error while closing the SSH session", e);
      }
    }
  }

  /** Types MINA understands in a pinned host-key entry. Documentation only. */
  static List<String> supportedHostKeyTypes() {
    return List.of("ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384");
  }
}
