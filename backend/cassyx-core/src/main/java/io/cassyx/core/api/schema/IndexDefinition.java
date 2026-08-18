package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** Input to CREATE INDEX - SAI, legacy 2i or DSE Search (plan sections 4 and 6). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IndexDefinition(
    String name,
    String target,
    IndexKind kind,
    String className,
    Map<String, String> options,
    Boolean ifNotExists) {

  /** The custom-index class implementing storage-attached indexing. */
  public static final String SAI_CLASS = "org.apache.cassandra.index.sai.StorageAttachedIndex";

  /** The custom-index class implementing DSE Search. */
  public static final String DSE_SEARCH_CLASS = "com.datastax.bdp.search.solr.Cql3SolrSecondaryIndex";

  public IndexDefinition {
    options = options == null ? Map.of() : Map.copyOf(options);
  }
}
