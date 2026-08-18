package io.cassyx.api.connections.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A custom-domain bundle option for one datacenter. */
public record AstraCustomDomainBundleView(
    String domain, @JsonProperty("downloadURL") String downloadUrl) {}
