package io.cassyx.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code cassyx.license.*} - plan section 9.2.
 *
 * @param enforce false means fully unlocked, no checks; the UI banner stays visible and a WARN is
 *     logged at startup so a bypassed instance is never mistaken for a paid one
 * @param key the Ed25519-signed license key
 * @param publicKey base64 X.509 Ed25519 PUBLIC key; only the public half ever ships
 */
@ConfigurationProperties(prefix = "cassyx.license")
public record LicenseProperties(boolean enforce, String key, String publicKey) {}
