package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Compression class, chunk length and CRC check chance. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompressionSettings(
    @JsonProperty("class") String compressionClass,
    Integer chunkLengthInKb,
    Double crcCheckChance) {}
