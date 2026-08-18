-- cassyx V2: everything the connections contract needs that the V1 baseline did not model.
--
-- V1 is immutable (it has shipped and Flyway checksums it), so this migration is additive only:
-- ALTER TABLE ... ADD COLUMN, never a redefinition. Two consequences worth stating:
--
--  * `protocol_version` in V1 is an INT, but the contract's ConnectionRequest.protocolVersion is an
--    ENUM of V3 | V4 | V5 | DSE_V1 | DSE_V2 - "DSE_V2" is not an integer. Rather than rewrite a
--    shipped column, V2 adds `protocol_version_name` and leaves the old one unused. The driver takes
--    the name, not the number.
--  * every new credential column keeps the V1 convention: `*_cipher` holds AES-256-GCM ciphertext
--    and `*_iv` the per-value 96-bit nonce. Nonces are stored, never derived - reuse under one key
--    is the single failure AES-GCM does not survive.
--
-- Nothing here is ever returned to a client. Responses expose has_* booleans (plan section 2.3).

ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS description VARCHAR(1000);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS protocol_version_name VARCHAR(20);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS default_keyspace VARCHAR(200);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS request_timeout_millis INT;
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS tags VARCHAR(1000);

-- Astra: the picker shows a name, and the stored bundle carries its own provenance so the UI can
-- say where it came from and spot a rotated one (plan section 3.1, deviations 4 and 5).
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS astra_database_name VARCHAR(200);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS scb_file_name VARCHAR(255);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS scb_size_bytes BIGINT;
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS scb_sha256 VARCHAR(64);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS scb_source VARCHAR(20);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS scb_cache_key VARCHAR(400);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS scb_validated BOOLEAN DEFAULT FALSE NOT NULL;

-- SSL / mTLS: V1 stored the material but not the metadata the response schema reports.
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS ssl_hostname_validation BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS ssl_cipher_suites VARCHAR(2000);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS truststore_file_name VARCHAR(255);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS truststore_type VARCHAR(20);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS keystore_file_name VARCHAR(255);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS keystore_type VARCHAR(20);

-- SSH tunnel: V1 modelled the host, user and private key only.
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS ssh_enabled BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS ssh_password_cipher VARBINARY(4096);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS ssh_password_iv VARBINARY(16);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS ssh_private_key_passphrase_cipher VARBINARY(4096);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS ssh_private_key_passphrase_iv VARBINARY(16);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS ssh_local_port INT;
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS ssh_remote_host VARCHAR(255);
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS ssh_remote_port INT;
-- Defaults to TRUE: a tunnel that accepts any host key protects nothing, so the safe value has to
-- be the one an existing row inherits.
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS ssh_strict_host_key_checking BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE cassyx_connection ADD COLUMN IF NOT EXISTS ssh_known_hosts_entry VARCHAR(2000);
