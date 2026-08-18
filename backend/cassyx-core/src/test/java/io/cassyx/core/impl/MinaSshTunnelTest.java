package io.cassyx.core.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.Secret;
import io.cassyx.core.api.SshTunnel;
import io.cassyx.core.api.SshTunnelSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SSH local port forward of plan section 3, end to end against an embedded SSH server.
 *
 * <p>An embedded server rather than a mock, because the two things worth proving here - that bytes
 * really traverse the forward, and that a wrong host key is rejected - are exactly the things a
 * mocked SSH client cannot prove.
 */
class MinaSshTunnelTest {

  private static final String USER = "ec2-user";
  private static final String PASSWORD = "bastion-password";

  @TempDir static Path hostKeyDir;

  private static SshServer sshd;
  private static ServerSocket target;
  private static ExecutorService echoPool;
  private static String knownHostsEntry;

  @BeforeAll
  static void startBastionAndTarget() throws Exception {
    // The "cluster" behind the bastion: an echo server standing in for a Cassandra node.
    target = new ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress());
    echoPool = Executors.newCachedThreadPool();
    echoPool.submit(MinaSshTunnelTest::acceptAndEchoForever);

    sshd = SshServer.setUpDefaultServer();
    sshd.setHost("127.0.0.1");
    sshd.setPort(0);
    sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyDir.resolve("hostkey.ser")));
    sshd.setPasswordAuthenticator((user, password, session) -> USER.equals(user) && PASSWORD.equals(password));
    sshd.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
    sshd.start();

    PublicKey hostKey = sshd.getKeyPairProvider().loadKeys(null).iterator().next().getPublic();
    knownHostsEntry = PublicKeyEntry.toString(hostKey);
  }

  @AfterAll
  static void stop() throws Exception {
    if (sshd != null) {
      sshd.stop(true);
    }
    if (target != null) {
      target.close();
    }
    if (echoPool != null) {
      echoPool.shutdownNow();
    }
  }

  @Test
  @DisplayName("bytes really traverse the forward, and the local port is loopback-only")
  void forwardsTrafficToTheTargetBehindTheBastion() throws Exception {
    try (SshTunnel tunnel = MinaSshTunnel.open(spec().build())) {
      assertThat(tunnel.isOpen()).isTrue();
      assertThat(tunnel.localPort()).isPositive();
      assertThat(tunnel.localContactPoint()).isEqualTo("127.0.0.1:" + tunnel.localPort());

      try (Socket socket = new Socket("127.0.0.1", tunnel.localPort())) {
        socket.getOutputStream().write("ping\n".getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        byte[] buffer = new byte[5];
        int read = socket.getInputStream().read(buffer);
        assertThat(new String(buffer, 0, read, StandardCharsets.UTF_8)).isEqualTo("ping\n");
      }
    }
  }

  @Test
  @DisplayName("localPort 0 lets the OS pick, so two tunnelled connections never collide")
  void picksAFreeLocalPort() throws Exception {
    try (SshTunnel first = MinaSshTunnel.open(spec().build());
        SshTunnel second = MinaSshTunnel.open(spec().build())) {
      assertThat(first.localPort()).isNotEqualTo(second.localPort());
    }
  }

  @Test
  void closingIsIdempotentAndNeverThrows() {
    SshTunnel tunnel = MinaSshTunnel.open(spec().build());

    tunnel.close();
    tunnel.close();

    assertThat(tunnel.isOpen()).isFalse();
  }

  @Test
  @DisplayName("a pinned host key that does not match is refused - a tunnel that trusts anyone is no tunnel")
  void rejectsAMismatchedHostKey() {
    SshTunnelSpec mismatched = spec().knownHostsEntry(otherHostKeyEntry()).build();

    assertThatThrownBy(() -> MinaSshTunnel.open(mismatched))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("Could not open the SSH tunnel");
  }

  @Test
  void rejectsBadCredentials() {
    SshTunnelSpec wrongPassword = spec().password("not-the-password").build();

    assertThatThrownBy(() -> MinaSshTunnel.open(wrongPassword))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("Could not open the SSH tunnel");
  }

  @Test
  @DisplayName("neither the password nor the key appears in the failure message")
  void neverLeaksCredentialsIntoTheError() {
    SshTunnelSpec wrongPassword = spec().password("hunter2-super-secret").build();

    assertThatThrownBy(() -> MinaSshTunnel.open(wrongPassword))
        .hasMessageNotContaining("hunter2-super-secret");
  }

  @Test
  void connectingToNothingFailsWithAnActionableMessage() {
    SshTunnelSpec unreachable =
        new SshTunnelSpec(
            "127.0.0.1",
            1,
            USER,
            Secret.of(PASSWORD),
            null,
            null,
            0,
            "127.0.0.1",
            target.getLocalPort(),
            false,
            null);

    assertThatThrownBy(() -> MinaSshTunnel.open(unreachable))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining(USER + "@127.0.0.1:1");
  }

  /* -------------------------------------------------------------- host key parsing */

  @Test
  void parsesAKnownHostsLineWithAHostPrefixAndAComment() {
    String line = "bastion.example.com " + knownHostsEntry + " operator@laptop";

    assertThat(MinaSshTunnel.parseHostKey(line)).isNotNull();
  }

  @Test
  @DisplayName("an unparseable pinned key fails closed - never falls back to accepting anything")
  void refusesAnUnparseableHostKey() {
    assertThatThrownBy(() -> MinaSshTunnel.parseHostKey("garbage"))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("known_hosts");
    assertThatThrownBy(() -> MinaSshTunnel.parseHostKey("ssh-ed25519 not-base64!!"))
        .isInstanceOf(CassyxCoreException.class);
    assertThatThrownBy(() -> MinaSshTunnel.parseHostKey(null))
        .isInstanceOf(CassyxCoreException.class);
    assertThatThrownBy(() -> MinaSshTunnel.parseHostKey("  "))
        .isInstanceOf(CassyxCoreException.class);
  }

  @Test
  void rejectsAPrivateKeyThatIsNotAKey() {
    SshTunnelSpec spec =
        new SshTunnelSpec(
            "127.0.0.1",
            22,
            USER,
            null,
            Secret.of("-----BEGIN OPENSSH PRIVATE KEY-----\nnot really\n-----END OPENSSH PRIVATE KEY-----"),
            null,
            0,
            "127.0.0.1",
            9042,
            false,
            null);

    assertThatThrownBy(() -> MinaSshTunnel.loadKeyPairs(spec))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("SSH private key could not be read");
  }

  @Test
  void documentsTheHostKeyTypesItAccepts() {
    assertThat(MinaSshTunnel.supportedHostKeyTypes()).contains("ssh-ed25519", "ssh-rsa");
  }

  /* ------------------------------------------------------------------- fixtures */

  private SpecBuilder spec() {
    return new SpecBuilder();
  }

  private final class SpecBuilder {

    private String password = PASSWORD;
    private String hostKey = knownHostsEntry;

    private SpecBuilder password(String value) {
      this.password = value;
      return this;
    }

    private SpecBuilder knownHostsEntry(String value) {
      this.hostKey = value;
      return this;
    }

    private SshTunnelSpec build() {
      return new SshTunnelSpec(
          "127.0.0.1",
          sshd.getPort(),
          USER,
          Secret.of(password),
          null,
          null,
          0,
          "127.0.0.1",
          target.getLocalPort(),
          true,
          hostKey);
    }
  }

  /** A real, valid entry for a DIFFERENT key, to prove pinning actually compares the bytes. */
  private static String otherHostKeyEntry() {
    try {
      java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return PublicKeyEntry.toString(generator.generateKeyPair().getPublic());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void acceptAndEchoForever() {
    while (!Thread.currentThread().isInterrupted() && !target.isClosed()) {
      try {
        Socket socket = target.accept();
        echoPool.submit(() -> echo(socket));
      } catch (IOException e) {
        return;
      }
    }
  }

  private static void echo(Socket socket) {
    try (Socket open = socket;
        InputStream in = open.getInputStream();
        OutputStream out = open.getOutputStream()) {
      byte[] buffer = new byte[1024];
      int read;
      while ((read = in.read(buffer)) > 0) {
        out.write(buffer, 0, read);
        out.flush();
      }
    } catch (IOException e) {
      // client went away
    }
  }
}
