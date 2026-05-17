-- =============================================================================
-- Schemat inicjalizacyjny PostGIS — dane OSM Wrocław
-- Źródło: wroclaw.geojson (3020 featur, wszystkie Point, SRID 4326)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Rozszerzenie PostGIS (wymagane raz na bazę)
-- -----------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS postgis;

-- -----------------------------------------------------------------------------
-- Tabela główna
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS wroclaw_features (

    -- Klucz główny
    id            SERIAL                PRIMARY KEY,

    -- Identyfikator z OSM (np. "relation/1348101", "node/123456")
    osm_id        TEXT                  NOT NULL UNIQUE,

    -- Geometria — Point, układ WGS84
    geom          GEOMETRY(Point, 4326) NOT NULL,

    -- Nazwy
    name          TEXT,                 -- główna nazwa
    alt_name      TEXT,                 -- alternatywna / popularna, np. "most Uniwersytecki"
    official_name TEXT,                 -- urzędowa, np. "Park Stanisława Staszica"
    short_name    TEXT,                 -- skrócona
    "name:en"     TEXT,                 -- po angielsku
    "name:pl"     TEXT,                 -- po polsku (gdy różni się od name)

    -- Opis tekstowy
    description   TEXT,

    -- Treść inskrypcji (tablice, pomniki)
    inscription   TEXT,

    -- Autor dzieła (krasnale, rzeźby, murale)
    artist_name   TEXT,

    -- Typ dzieła: 'dwarf' | 'mural' | 'sculpture' | 'statue' | 'graffiti' | ...
    artwork_type  TEXT,

    -- Wszystkie pozostałe tagi OSM
    -- Zawiera m.in.: tourism, leisure, historic, highway, wikidata, image,
    -- start_date, network, material, access, name:de, ref:*, source:*, itp.
    tags          JSONB                 NOT NULL DEFAULT '{}'::jsonb

);

-- -----------------------------------------------------------------------------
-- Indeksy
-- -----------------------------------------------------------------------------

-- Przestrzenny (obowiązkowy dla zapytań geograficznych)
CREATE INDEX idx_wroclaw_geom
    ON wroclaw_features USING GIST (geom);

-- artwork_type — główny filtr dla krasnali, rzeźb, murali
CREATE INDEX idx_wroclaw_artwork_type
    ON wroclaw_features (artwork_type)
    WHERE artwork_type IS NOT NULL;

-- Wyszukiwanie po nazwie
CREATE INDEX idx_wroclaw_name
    ON wroclaw_features (name)
    WHERE name IS NOT NULL;

-- JSONB — GIN dla zapytań @>, ?, ?|, ?& na tagach
-- Umożliwia np.: WHERE tags @> '{"tourism": "artwork"}'
CREATE INDEX idx_wroclaw_tags_gin
    ON wroclaw_features USING GIN (tags);

-- -----------------------------------------------------------------------------
-- Komentarze dokumentacyjne
-- -----------------------------------------------------------------------------
COMMENT ON TABLE wroclaw_features IS
    'Dane OSM dla Wrocławia — 3020 punktów (parki, krasnale, pomniki, mosty, drogi i inne). '
    'Źródło: wroclaw.geojson. Dedykowane kolumny dla kluczowych pól; '
    'wszystkie pozostałe tagi OSM są w kolumnie tags (JSONB).';

COMMENT ON COLUMN wroclaw_features.osm_id IS
    'Oryginalny identyfikator OSM z pola @id, np. "relation/1348101" lub "node/123456".';

COMMENT ON COLUMN wroclaw_features.geom IS
    'Geometria punktowa w układzie WGS84 (SRID 4326).';

COMMENT ON COLUMN wroclaw_features.artwork_type IS
    'Typ dzieła z OSM: dwarf | mural | sculpture | statue | graffiti | installation | relief | mosaic';

COMMENT ON COLUMN wroclaw_features.tags IS
    'Wszystkie tagi OSM bez dedykowanej kolumny. '
    'Zawiera m.in.: tourism, leisure, historic, highway, wikidata, wikipedia, '
    'image, url, website, start_date, network, material, surface, access, '
    'name:de, ref:*, source:*, species, itp.';
