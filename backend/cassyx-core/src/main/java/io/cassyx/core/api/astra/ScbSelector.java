package io.cassyx.core.api.astra;

import java.util.Optional;

/**
 * Which bundle to pick out of the {@code secureBundleURL} response.
 *
 * <p>Two ORTHOGONAL inputs (plan section 3.1):
 *
 * <ul>
 *   <li>{@code region} - optional, matched case-insensitively; absent means "first element"
 *   <li>{@code scbType} - {@link ScbType#DEFAULT} or {@link ScbType#CUSTOM}; {@code domain} is
 *       required if and only if the type is CUSTOM
 * </ul>
 */
public record ScbSelector(String region, ScbType scbType, String domain) {

  public ScbSelector {
    scbType = scbType == null ? ScbType.DEFAULT : scbType;
    region = (region == null || region.isBlank()) ? null : region.trim();
    domain = (domain == null || domain.isBlank()) ? null : domain.trim();
    if (scbType == ScbType.CUSTOM && domain == null) {
      throw new IllegalArgumentException("domain is required when scbType is CUSTOM");
    }
    if (scbType == ScbType.DEFAULT && domain != null) {
      throw new IllegalArgumentException("domain must not be set when scbType is DEFAULT");
    }
  }

  public static ScbSelector defaultBundle() {
    return new ScbSelector(null, ScbType.DEFAULT, null);
  }

  public static ScbSelector defaultBundleIn(String region) {
    return new ScbSelector(region, ScbType.DEFAULT, null);
  }

  public static ScbSelector customDomain(String region, String domain) {
    return new ScbSelector(region, ScbType.CUSTOM, domain);
  }

  public Optional<String> regionOpt() {
    return Optional.ofNullable(region);
  }

  /** Cache key per plan section 3.1, deviation 5. */
  public String cacheKey(String databaseId) {
    return databaseId + "|" + (region == null ? "*" : region.toLowerCase(java.util.Locale.ROOT))
        + "|" + scbType + "|" + (domain == null ? "" : domain);
  }
}
