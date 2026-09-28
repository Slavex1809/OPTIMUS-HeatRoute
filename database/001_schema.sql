CREATE EXTENSION IF NOT EXISTS postgis;
CREATE TABLE IF NOT EXISTS calculation (
 id uuid PRIMARY KEY, created timestamptz NOT NULL DEFAULT now(), state jsonb NOT NULL
);
CREATE INDEX IF NOT EXISTS calculation_created_idx ON calculation(created DESC);
CREATE TABLE IF NOT EXISTS input_feature (
 calculation_id uuid NOT NULL REFERENCES calculation(id) ON DELETE CASCADE,
 id_key text NOT NULL, original_id jsonb NOT NULL, object_type text NOT NULL,
 restriction_type text, diameter integer, flow_tph numeric,
 geom geometry(Geometry,32637) NOT NULL,
 PRIMARY KEY(calculation_id,id_key),
 CHECK (NOT ST_IsEmpty(geom) AND ST_IsValid(geom) AND ST_NDims(geom)=2),
 CHECK (flow_tph IS NULL OR (flow_tph>=0 AND flow_tph<'Infinity'::numeric))
);
CREATE INDEX IF NOT EXISTS input_geometry_idx ON input_feature USING gist(geom);
CREATE INDEX IF NOT EXISTS input_type_idx ON input_feature(calculation_id,object_type);
CREATE OR REPLACE FUNCTION optimus_metric_geojson(g jsonb) RETURNS geometry LANGUAGE sql IMMUTABLE STRICT AS $$
 SELECT ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(g::text),4326),32637)
$$;
CREATE OR REPLACE FUNCTION optimus_score(cost numeric,length_m numeric) RETURNS numeric LANGUAGE sql IMMUTABLE STRICT AS $$
 SELECT .7*cost/25000000 + .3*length_m/100
$$;
