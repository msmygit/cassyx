package io.cassyx.licensing;

import io.cassyx.licensing.config.LicensingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The operator-run licence minting service (plan sections 9.1, 9.3, 9.4).
 *
 * <p>This is a SEPARATE deployment from the product on purpose: minting needs the Ed25519 PRIVATE
 * key, and that key must never ship inside the self-hosted image. The distributed application only
 * ever verifies, with the public half. Consequently nothing here is customer-installable and
 * nothing in cassyx-api depends on it at build time.
 */
@SpringBootApplication
@EnableConfigurationProperties(LicensingProperties.class)
public class LicensingApplication {

  public static void main(String[] args) {
    SpringApplication.run(LicensingApplication.class, args);
  }
}
