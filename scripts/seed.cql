-- ============================================================================
-- cassyx demo seed — plan §2.2
--
-- "The seed dataset deliberately exercises the hard paths: collections, UDTs,
--  tuples, counters, blobs, static columns, a vector<float,1536> column with
--  an SAI index, a deliberately skewed partition (for the §5.2 work-stealing
--  test), and a wide 1000-column table (for the grid benchmark)."
--
-- This file is idempotent (IF NOT EXISTS everywhere) and is applied by
-- scripts/seed.sh, which then generates the volume-scaled data:
--   * cassyx_demo.wide_grid       (SEED_WIDE_COLUMNS columns — DDL is generated)
--   * cassyx_demo.doc_embeddings  (SEED_VECTOR_DIM-dimensional vectors)
--   * the skewed partition + event rows at SEED_SCALE volume
--
-- Applying this file on its own gives a complete, browsable schema plus a
-- small representative row in every table. Requires Cassandra 5.x (vector+SAI).
-- ============================================================================

CREATE KEYSPACE IF NOT EXISTS cassyx_demo
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}
   AND durable_writes = true;

USE cassyx_demo;

-- ---------------------------------------------------------------------------
-- UDTs (§4: nested-type rendering in the grid)
-- ---------------------------------------------------------------------------
CREATE TYPE IF NOT EXISTS address (
  street   text,
  city     text,
  country  text,
  zip      text,
  geo      frozen<tuple<double, double>>
);

CREATE TYPE IF NOT EXISTS audit_info (
  created_by  text,
  created_at  timestamp,
  source_ip   inet
);

-- ---------------------------------------------------------------------------
-- 1. Kitchen-sink table: collections, frozen UDTs, tuples, blob, inet,
--    duration, timeuuid, decimal/varint, and a STATIC column.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
  tenant_id      text,
  user_id        uuid,
  tenant_name    text STATIC,               -- static column
  tenant_plan    text STATIC,               -- static column
  email          text,
  display_name   text,
  tags           set<text>,                 -- set
  roles          list<text>,                -- list
  attributes     map<text, text>,           -- map
  home_address   frozen<address>,           -- UDT
  known_addrs    list<frozen<address>>,     -- collection of UDTs
  login_window   frozen<tuple<text, int, timestamp>>,  -- tuple
  avatar         blob,                      -- blob
  last_login_ip  inet,
  session_ttl    duration,
  balance        decimal,
  big_number     varint,
  event_ref      timeuuid,
  is_active      boolean,
  audit          frozen<audit_info>,
  created_at     timestamp,
  PRIMARY KEY ((tenant_id), user_id)
) WITH CLUSTERING ORDER BY (user_id ASC)
   AND comment = 'Kitchen-sink table: collections, UDTs, tuples, blobs, static columns.'
   AND compaction = {'class': 'SizeTieredCompactionStrategy'}
   AND compression = {'class': 'LZ4Compressor'}
   AND gc_grace_seconds = 864000
   AND default_time_to_live = 0;

CREATE INDEX IF NOT EXISTS users_email_sai ON users (email) USING 'StorageAttachedIndex'
  WITH OPTIONS = {'case_sensitive': 'false', 'normalize': 'true'};
CREATE INDEX IF NOT EXISTS users_tags_sai  ON users (VALUES(tags)) USING 'StorageAttachedIndex';

INSERT INTO users (tenant_id, user_id, tenant_name, tenant_plan, email, display_name,
                   tags, roles, attributes, home_address, known_addrs, login_window,
                   avatar, last_login_ip, session_ttl, balance, big_number, event_ref,
                   is_active, audit, created_at)
VALUES ('acme', 11111111-1111-1111-1111-111111111111, 'ACME Corp', 'enterprise',
        'ada@acme.example', 'Ada Lovelace',
        {'admin', 'early-adopter'},
        ['ROLE_ADMIN', 'ROLE_USER'],
        {'theme': 'dark', 'locale': 'en-GB'},
        {street: '1 Analytical Way', city: 'London', country: 'GB', zip: 'EC1A 1AA',
         geo: (51.5074, -0.1278)},
        [{street: '2 Difference St', city: 'Cambridge', country: 'GB', zip: 'CB2 1TN',
          geo: (52.2053, 0.1218)}],
        ('weekday', 9, '2026-01-01T09:00:00Z'),
        textAsBlob('avatar-bytes-ada'),
        '10.0.0.7',
        3h20m,
        1234.5678,
        170141183460469231731687303715884105727,
        now(),
        true,
        {created_by: 'seed', created_at: '2026-01-01T00:00:00Z', source_ip: '127.0.0.1'},
        '2026-01-01T00:00:00Z');

INSERT INTO users (tenant_id, user_id, email, display_name, tags, roles, attributes,
                   avatar, last_login_ip, is_active, created_at)
VALUES ('acme', 22222222-2222-2222-2222-222222222222,
        'grace@acme.example', 'Grace Hopper',
        {'ops'}, ['ROLE_USER'], {'theme': 'light'},
        0x00ff00ff00ff, '10.0.0.8', true, '2026-02-02T00:00:00Z');

-- Null-vs-unset visibility (§7): this row leaves most columns UNSET, not null.
INSERT INTO users (tenant_id, user_id, display_name)
VALUES ('globex', 33333333-3333-3333-3333-333333333333, 'Unset Example');

-- ---------------------------------------------------------------------------
-- 2. Counters (dedicated table — counters cannot mix with regular columns).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS page_counters (
  tenant_id  text,
  page       text,
  views      counter,
  clicks     counter,
  PRIMARY KEY ((tenant_id), page)
) WITH comment = 'Counter table — batch.maxBatchStatements must drop to 1 here (§5.3).';

UPDATE page_counters SET views = views + 1042, clicks = clicks + 87
  WHERE tenant_id = 'acme' AND page = '/dashboard';
UPDATE page_counters SET views = views + 17,   clicks = clicks + 2
  WHERE tenant_id = 'acme' AND page = '/settings';

-- ---------------------------------------------------------------------------
-- 3. Time-series events — the "normal", evenly distributed table.
--    Scaled to SEED_EVENTS * SEED_SCALE rows by scripts/seed.sh.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_events (
  tenant_id   text,
  bucket      int,
  event_id    timeuuid,
  event_type  text,
  bucket_desc text STATIC,          -- static column on a time-series partition
  payload     map<text, text>,
  size_bytes  int,
  raw         blob,
  PRIMARY KEY ((tenant_id, bucket), event_id)
) WITH CLUSTERING ORDER BY (event_id DESC)
   AND comment = 'Evenly distributed time-series events.';

CREATE INDEX IF NOT EXISTS app_events_type_sai
  ON app_events (event_type) USING 'StorageAttachedIndex';

INSERT INTO app_events (tenant_id, bucket, event_id, event_type, bucket_desc,
                        payload, size_bytes, raw)
VALUES ('acme', 0, now(), 'login', 'seed bucket 0', {'ua': 'curl'}, 128,
        textAsBlob('hello'));

-- ---------------------------------------------------------------------------
-- 4. DELIBERATELY SKEWED partition (§5.2 work-stealing path).
--    Partition 'HOT' receives SEED_SKEW_ROWS rows while every other partition
--    receives a handful. splitEvenly() splits by TOKEN COUNT, not data volume,
--    so an equal-token split over this table is wildly unequal in time — which
--    is exactly the condition the work-stealing queue must absorb.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sensor_readings (
  sensor_id   text,
  reading_ts  timestamp,
  value       double,
  quality     text,
  meta        map<text, text>,
  PRIMARY KEY ((sensor_id), reading_ts)
) WITH CLUSTERING ORDER BY (reading_ts DESC)
   AND comment = 'Skew fixture: partition sensor_id=''HOT'' holds ~99% of all rows.';

INSERT INTO sensor_readings (sensor_id, reading_ts, value, quality, meta)
VALUES ('HOT', '2026-01-01T00:00:00Z', 0.0, 'good', {'seed': 'true'});
INSERT INTO sensor_readings (sensor_id, reading_ts, value, quality, meta)
VALUES ('cold-0001', '2026-01-01T00:00:00Z', 21.5, 'good', {'seed': 'true'});

-- ---------------------------------------------------------------------------
-- 5. Vector + SAI (§6). vector<float, 1536> with an ANN-capable SAI index.
--    Dimension is fixed at 1536 here; scripts/seed.sh recreates this table at
--    SEED_VECTOR_DIM if it is overridden.
--    Query shape: SELECT ... ORDER BY embedding ANN OF [...] LIMIT k
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS doc_embeddings (
  doc_id     uuid,
  chunk_no   int,
  title      text,
  body       text,
  category   text,
  embedding  vector<float, 1536>,
  meta       map<text, text>,
  updated_at timestamp,
  PRIMARY KEY ((doc_id), chunk_no)
) WITH CLUSTERING ORDER BY (chunk_no ASC)
   AND comment = 'Vector search fixture: vector<float,1536> + SAI (cosine).';

CREATE INDEX IF NOT EXISTS doc_embeddings_ann
  ON doc_embeddings (embedding) USING 'StorageAttachedIndex'
  WITH OPTIONS = {'similarity_function': 'cosine'};

-- Hybrid query fixture: SAI predicate + ANN in one statement (§6).
CREATE INDEX IF NOT EXISTS doc_embeddings_category_sai
  ON doc_embeddings (category) USING 'StorageAttachedIndex';

-- Vector rows are generated (1536 floats each) by scripts/seed.sh.

-- ---------------------------------------------------------------------------
-- 6. Materialized view (§4) over app_events — capability-gated in the UI.
-- ---------------------------------------------------------------------------
CREATE MATERIALIZED VIEW IF NOT EXISTS app_events_by_type AS
  SELECT tenant_id, bucket, event_type, event_id, size_bytes
  FROM app_events
  WHERE tenant_id IS NOT NULL AND bucket IS NOT NULL
    AND event_type IS NOT NULL AND event_id IS NOT NULL
  PRIMARY KEY ((tenant_id, bucket), event_type, event_id);

-- ---------------------------------------------------------------------------
-- 7. Job/export target used by E2E: a small, stable table that is safe to
--    truncate and re-load without touching the fixtures above.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS e2e_scratch (
  id     uuid PRIMARY KEY,
  label  text,
  n      int
) WITH comment = 'Safe to TRUNCATE — used by the Playwright smoke journey.';

INSERT INTO e2e_scratch (id, label, n)
VALUES (44444444-4444-4444-4444-444444444444, 'smoke', 1);

-- The wide ~1000-column grid-benchmark table (cassyx_demo.wide_grid) is
-- generated by scripts/seed.sh — 1000 columns of literal DDL does not belong
-- in a hand-maintained file, and the width is configurable via
-- SEED_WIDE_COLUMNS so `make bench` can scale it.
