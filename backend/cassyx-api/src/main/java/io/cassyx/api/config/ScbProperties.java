package io.cassyx.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code cassyx.scb.*} - the allow-list root for PATH-mode bundles (plan section 3). */
@ConfigurationProperties(prefix = "cassyx.scb")
public record ScbProperties(String pathRoot) {}
