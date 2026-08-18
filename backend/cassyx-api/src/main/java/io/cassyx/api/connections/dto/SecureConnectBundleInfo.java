package io.cassyx.api.connections.dto;

import java.time.Instant;

/**
 * Metadata about the stored secure connect bundle. The bytes live encrypted in H2 and are NEVER
 * returned; they are materialised to a session-scoped temp file at connect time and that path is
 * never exposed either.
 *
 * @param sha256 integrity digest - the cheapest way to spot that Astra rotated the bundle
 * @param cacheKey {@code (databaseId, region, scbType, domain)}, per plan section 3.1 deviation 5
 */
public record SecureConnectBundleInfo(
    String fileName,
    long sizeBytes,
    String sha256,
    Instant storedAt,
    ScbMode source,
    String region,
    ScbType scbType,
    String domain,
    String cacheKey,
    boolean validated) {}
