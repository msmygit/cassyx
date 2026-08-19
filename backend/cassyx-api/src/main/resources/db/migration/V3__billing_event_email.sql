-- cassyx V3: one additive column on the V1 webhook idempotency ledger (plan section 9.3).
--
-- V1 already models `cassyx_billing_event` (event_id PRIMARY KEY is the idempotency mechanism:
-- concurrent deliveries of one Stripe event race in the database, and exactly one wins). What it
-- does not record is WHO the event was for, and that is the first question asked when a buyer says
-- "I paid and got nothing" - answering it from Stripe's dashboard alone means correlating by hand.
--
-- V1 has shipped and Flyway checksums it, so this is ADD COLUMN, never a redefinition.
-- No card data, no Stripe secret and no licence key is ever stored here.

ALTER TABLE cassyx_billing_event ADD COLUMN IF NOT EXISTS email VARCHAR(320);
