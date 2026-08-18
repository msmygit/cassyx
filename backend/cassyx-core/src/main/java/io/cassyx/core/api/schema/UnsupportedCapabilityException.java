package io.cassyx.core.api.schema;

import io.cassyx.core.api.Capability;
import io.cassyx.core.api.CassyxCoreException;

/**
 * The target cluster does not support this feature (plan section 7.1). The UI hides such features
 * behind an explanation; this exists so a direct API call still fails legibly.
 */
public class UnsupportedCapabilityException extends CassyxCoreException {

  private static final long serialVersionUID = 1L;

  private final Capability capability;

  public UnsupportedCapabilityException(Capability capability, String message) {
    super(message);
    this.capability = capability;
  }

  public Capability capability() {
    return capability;
  }
}
