package io.cassyx.core.api.astra;

/** An element of {@code customDomainBundles[]} in the secureBundleURL response. */
public record CustomDomainBundle(String domain, String downloadUrl) {}
