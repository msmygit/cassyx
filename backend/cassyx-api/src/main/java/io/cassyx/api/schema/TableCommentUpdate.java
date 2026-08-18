package io.cassyx.api.schema;

/** Body of {@code PUT .../tables/{table}/comment} - the editable COMMENT tab. */
public record TableCommentUpdate(String comment) {}
