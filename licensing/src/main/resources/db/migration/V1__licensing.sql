-- cassyx licensing service, baseline (plan sections 9.1, 9.3, 9.4).
--
-- This database is the ONLY record that a licence was ever minted. Stripe knows a payment
-- happened; only this table knows which key that payment produced, which is what makes the
-- self-serve recovery endpoint possible ("email me my key again" is the top support ticket).

CREATE TABLE cassyx_issued_license (
  id             VARCHAR(36)  NOT NULL PRIMARY KEY,
  lic_code       VARCHAR(64)  NOT NULL,
  email          VARCHAR(320) NOT NULL,
  holder_name    VARCHAR(200),
  edition        VARCHAR(40)  NOT NULL,
  seats          INT          NOT NULL,
  payload_ver    INT          NOT NULL,
  issued_on      DATE         NOT NULL,
  expires_on     DATE,                      -- NULL = perpetual (plan section 9.4)
  scope_major    INT,                       -- NULL = unrestricted (plan section 9.5)
  license_key    CLOB         NOT NULL,     -- payload.signature, exactly as the customer gets it
  source_event   VARCHAR(255),              -- Stripe event id, or NULL for trials/manual issues
  created_at     TIMESTAMP    NOT NULL,
  -- Delivery is tracked separately from minting because they fail independently: a key that was
  -- minted but not emailed is recoverable, and must be visibly PENDING until it is delivered.
  delivery_state VARCHAR(20)  NOT NULL,     -- PENDING | SENT | FAILED
  delivery_error VARCHAR(1000),
  attempts       INT          DEFAULT 0 NOT NULL,
  last_attempt   TIMESTAMP,
  -- Set to the email ONLY on trial rows, NULL otherwise, and uniquely indexed below. A unique
  -- index over (email, edition) would have been the obvious move and is wrong: it would also cap
  -- a paying customer at one purchase per address.
  trial_email    VARCHAR(320)
);

CREATE INDEX ix_issued_license_email ON cassyx_issued_license (email);
CREATE INDEX ix_issued_license_state ON cassyx_issued_license (delivery_state);

-- One trial per email, enforced by the database rather than by a read-then-write in Java: two
-- concurrent requests would otherwise both see "no trial yet" and hand out an endless renewal
-- loop. NULL repeats freely in a unique index, so purchases (trial_email IS NULL) are unaffected.
CREATE UNIQUE INDEX ux_issued_trial_email ON cassyx_issued_license (trial_email);

-- Webhook idempotency for events delivered straight to this service (plan section 9.3).
CREATE TABLE cassyx_licensing_event (
  event_id    VARCHAR(255) NOT NULL PRIMARY KEY,
  event_type  VARCHAR(120) NOT NULL,
  received_at TIMESTAMP    NOT NULL,
  status      VARCHAR(40)  NOT NULL,
  email       VARCHAR(320),
  detail      VARCHAR(1000)
);
