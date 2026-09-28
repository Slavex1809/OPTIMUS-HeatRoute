package com.heatroute.ingest;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.heatroute.domain.Model.*;
import com.heatroute.geo.Metric;
import com.heatroute.hydraulic.DiameterCatalog;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.geojson.GeoJsonReader;

public final class GeoJsonInput {
  public static final ObjectMapper JSON =
      new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  public static final Set<String> RULES =
      Set.of(
          "oks",
          "park",
          "social_area",
          "prohibited_site",
          "water",
          "railway",
          "road",
          "tram_tracks",
          "gas_pipeline",
          "power_cable");

  public Snapshot read(InputStream input) throws IOException {
    List<Feature> fs = new ArrayList<>();
    stream(input, fs::add);
    Snapshot s = new Snapshot(fs);
    if (s.targets.isEmpty() || s.networks.isEmpty())
      throw new IOException("Required heat_network and oks_connection_point");
    return s;
  }

  public Snapshot read(InputStream input, java.nio.file.Path spool) throws IOException {
    GeometryStore store = new GeometryStore(spool);
    List<Feature> fs = new ArrayList<>();
    try {
      stream(
          input,
          f -> {
            try {
              fs.add(f.spooled(store));
            } catch (IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          });
      Snapshot s = new Snapshot(fs).withStore(store);
      if (s.targets.isEmpty() || s.networks.isEmpty())
        throw new IOException("Required heat_network and oks_connection_point");
      return s;
    } catch (Exception e) {
      store.close();
      throw e;
    }
  }

  public long stream(InputStream input, Consumer<Feature> consumer) throws IOException {
    Set<String> ids = new HashSet<>();
    long count = 0;
    String type = null;
    boolean features = false;
    try (JsonParser p = JSON.getFactory().createParser(input)) {
      p.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      if (p.nextToken() != JsonToken.START_OBJECT)
        throw new IOException("Expected FeatureCollection");
      while (p.nextToken() != JsonToken.END_OBJECT) {
        if (p.currentToken() != JsonToken.FIELD_NAME)
          throw new IOException("Invalid JSON envelope");
        String field = p.currentName();
        p.nextToken();
        if (field.equals("type")) {
          type = p.getValueAsString();
          p.skipChildren();
        } else if (field.equals("crs")) {
          JsonNode crs = JSON.readTree(p);
          String name = crs.path("properties").path("name").asText();
          if (!Set.of("EPSG:4326", "urn:ogc:def:crs:EPSG::4326", "urn:ogc:def:crs:OGC:1.3:CRS84")
              .contains(name)) throw new IOException("Input CRS must be WGS84");
        } else if (field.equals("features")) {
          features = true;
          if (p.currentToken() != JsonToken.START_ARRAY)
            throw new IOException("Expected features array");
          while (p.nextToken() != JsonToken.END_ARRAY) {
            if (p.currentToken() != JsonToken.START_OBJECT)
              throw new IOException("Expected feature");
            Feature f = parse(readFeature(p));
            if (!ids.add(f.key)) throw new IOException("Duplicate id " + f.id);
            consumer.accept(f);
            count++;
          }
        } else p.skipChildren();
      }
      if (!features || !"FeatureCollection".equals(type) || p.nextToken() != null)
        throw new IOException("Invalid FeatureCollection");
    }
    return count;
  }

  private JsonNode readFeature(JsonParser p) throws IOException {
    var feature = JSON.createObjectNode();
    while (p.nextToken() != JsonToken.END_OBJECT) {
      if (p.currentToken() != JsonToken.FIELD_NAME) throw new IOException("Invalid feature object");
      String field = p.currentName();
      p.nextToken();
      if (field.equals("geometry") || field.equals("type")) feature.set(field, JSON.readTree(p));
      else if (field.equals("properties")) {
        if (p.currentToken() != JsonToken.START_OBJECT)
          throw new IOException("Expected properties object");
        var properties = JSON.createObjectNode();
        while (p.nextToken() != JsonToken.END_OBJECT) {
          String key = p.currentName();
          p.nextToken();
          if (Set.of("id", "object_type", "restriction_type", "diameter", "flow_tph").contains(key))
            properties.set(key, JSON.readTree(p));
          else p.skipChildren();
        }
        feature.set("properties", properties);
      } else p.skipChildren();
    }
    return feature;
  }

  private Feature parse(JsonNode f) throws IOException {
    JsonNode p = f.path("properties"), id = p.path("id");
    String type = p.path("object_type").asText(), rt = p.path("restriction_type").asText("");
    if (!f.path("type").asText().equals("Feature")
        || !(id.isNumber() || id.isTextual())
        || (id.isTextual() && id.asText().isBlank()))
      throw new IOException("Feature requires string/number id");
    if (!Set.of("source", "heat_network", "heat_chamber", "oks_connection_point", "restriction")
        .contains(type)) throw new IOException("Unsupported object_type at " + id);
    try {
      Geometry g = new GeoJsonReader().read(f.path("geometry").toString());
      if (g.isEmpty() || !g.isValid()) throw new IOException("Invalid geometry at " + id);
      for (Coordinate c : g.getCoordinates())
        if (!Double.isFinite(c.x)
            || !Double.isFinite(c.y)
            || Math.abs(c.x) > 180
            || Math.abs(c.y) > 90
            || !Double.isNaN(c.getZ()))
          throw new IOException("Expected 2D WGS84 coordinates at " + id);
      if (Set.of("source", "heat_chamber", "oks_connection_point").contains(type)
          && !(g instanceof Point)) throw new IOException("Expected Point at " + id);
      int diameter = 0;
      BigDecimal flow = BigDecimal.ZERO;
      if (type.equals("heat_network")) {
        if (!(g instanceof LineString)
            || !g.isSimple()
            || g.getLength() == 0
            || !p.path("diameter").isIntegralNumber())
          throw new IOException("Invalid network at " + id);
        diameter = p.path("diameter").intValue();
        DiameterCatalog.get(diameter);
      }
      if (type.equals("oks_connection_point")) {
        if (!p.path("flow_tph").isNumber()) throw new IOException("Missing flow_tph at " + id);
        flow = p.get("flow_tph").decimalValue();
        if (flow.signum() < 0) throw new IOException("Negative flow at " + id);
      }
      if (type.equals("restriction")
          && (rt.isBlank()
              || !(g instanceof LineString
                  || g instanceof MultiLineString
                  || g instanceof Polygon
                  || g instanceof MultiPolygon)))
        throw new IOException("Invalid restriction at " + id);
      return new Feature(id, type, rt, Metric.transform(g, true), diameter, flow);
    } catch (org.locationtech.jts.io.ParseException | IllegalArgumentException e) {
      throw new IOException("Invalid feature " + id + ": " + e.getMessage(), e);
    }
  }
}
