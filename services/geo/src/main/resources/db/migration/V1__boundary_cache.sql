-- A9.4 — durable boundary cache for the geo grounding service.
--
-- Resolved administrative / POI boundaries (OSM Nominatim, later ČÚZK RÚIAN) are cached here so the
-- service honours OSM's rate-limit + caching policy ACROSS restarts and shares boundaries between
-- pods. `fetched_at_ms` drives a staleness TTL (refresh-on-read). `wkt` + the bbox columns are NULL
-- for a centroid-only hit (no polygon returned). `place_alias` maps declined / alternate surface
-- forms ("Brna") onto a canonical `place_ref` ("brno") so they share one cached boundary.
CREATE TABLE IF NOT EXISTS boundary_cache (
    place_ref     VARCHAR(512) PRIMARY KEY,
    label         VARCHAR(1024)    NOT NULL,
    lat           DOUBLE PRECISION NOT NULL,
    lon           DOUBLE PRECISION NOT NULL,
    wkt           TEXT,
    min_lat       DOUBLE PRECISION,
    min_lon       DOUBLE PRECISION,
    max_lat       DOUBLE PRECISION,
    max_lon       DOUBLE PRECISION,
    source        VARCHAR(128)     NOT NULL,
    attribution   VARCHAR(512)     NOT NULL DEFAULT '',
    fetched_at_ms BIGINT           NOT NULL
);

CREATE TABLE IF NOT EXISTS place_alias (
    alias     VARCHAR(512) PRIMARY KEY,
    place_ref VARCHAR(512) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_place_alias_place_ref ON place_alias (place_ref);
