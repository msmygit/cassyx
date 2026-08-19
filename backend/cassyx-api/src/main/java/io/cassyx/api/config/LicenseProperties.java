package io.cassyx.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code cassyx.license.*} - plan section 9.2.
 *
 * @param enforce false means fully unlocked, no checks; the UI banner stays visible and a WARN is
 *     logged at startup so a bypassed instance is never mistaken for a paid one
 * @param bypassAllowed BUILD-time gate on {@code enforce}, filtered in by the Maven profile: true
 *     in a {@code dev} build, false in the {@code release} build that ships as the Docker image.
 *     When false, {@code enforce=false} is ignored and enforcement stays on. It is not an env var
 *     on purpose - a runtime switch guarding a runtime switch would guard nothing
 * @param key the Ed25519-signed license key
 * @param publicKey base64 X.509 Ed25519 PUBLIC key; only the public half ever ships
 */
@ConfigurationProperties(prefix = "cassyx.license")
public record LicenseProperties(
    boolean enforce, boolean bypassAllowed, String key, String publicKey) {}
