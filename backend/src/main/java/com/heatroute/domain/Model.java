package com.heatroute.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.heatroute.ingest.GeometryStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.ItemDistance;
import org.locationtech.jts.index.strtree.STRtree;

public final class Model {
  public static String key(JsonNode id) {
    return id.isTextual()
        ? "s:" + id.textValue()
        : "n:" + id.decimalValue().stripTrailingZeros().toPlainString();
  }

  public static final class Feature {
    public final JsonNode id;
    public final String key, type, restriction;
    private final Geometry geometry;
    public final Envelope envelope;
    private final GeometryStore store;
    private final GeometryStore.Ref ref;
    public final int diameter;
    public final BigDecimal flow;

    public Feature(
        JsonNode id,
        String type,
        String restriction,
        Geometry geometry,
        int diameter,
        BigDecimal flow) {
      this.id = id.deepCopy();
      key = key(id);
      this.type = type;
      this.restriction = restriction;
      this.geometry = geometry;
      this.envelope = new Envelope(geometry.getEnvelopeInternal());
      this.store = null;
      this.ref = null;
      this.diameter = diameter;
      this.flow = flow;
    }

    public Geometry geometry() {
      return geometry != null ? geometry : store.get(ref);
    }

    private Feature(Feature f, GeometryStore store, GeometryStore.Ref ref) {
      id = f.id;
      key = f.key;
      type = f.type;
      restriction = f.restriction;
      diameter = f.diameter;
      flow = f.flow;
      envelope = f.envelope;
      geometry = null;
      this.store = store;
      this.ref = ref;
    }

    public Feature spooled(GeometryStore store) throws IOException {
      return new Feature(this, store, store.put(geometry()));
    }
  }

  public static final class Snapshot implements AutoCloseable {
    public final List<Feature> features,
        targets = new ArrayList<>(),
        networks = new ArrayList<>(),
        chambers = new ArrayList<>(),
        obstacles = new ArrayList<>();
    public final Map<String, Feature> byId = new TreeMap<>();
    public final STRtree index = new STRtree(),
        networkIndex = new STRtree(),
        chamberIndex = new STRtree();
    private GeometryStore geometryStore;
    public final List<String> warnings = new ArrayList<>();

    public Snapshot(List<Feature> source) {
      features = new ArrayList<>(source);
      features.sort(Comparator.comparing(f -> f.key));
      for (Feature f : features) {
        byId.put(f.key, f);
        if (f.type.equals("oks_connection_point")) targets.add(f);
        if (f.type.equals("heat_network")) {
          networks.add(f);
          networkIndex.insert(f.envelope, f);
        }
        if (f.type.equals("heat_chamber")) {
          chambers.add(f);
          chamberIndex.insert(f.envelope, f);
        }
        if (f.type.equals("restriction") || f.type.equals("heat_network")) {
          obstacles.add(f);
          index.insert(f.envelope, f);
        }
      }
      index.build();
      networkIndex.build();
      chamberIndex.build();
    }

    public Snapshot withStore(GeometryStore store) {
      geometryStore = store;
      return this;
    }

    public void close() throws IOException {
      if (geometryStore != null) geometryStore.close();
    }

    @SuppressWarnings("unchecked")
    public List<Feature> chambersNear(Coordinate p, double radius) {
      Envelope e = new Envelope(p);
      e.expandBy(radius);
      List<Feature> fs = new ArrayList<>(chamberIndex.query(e));
      fs.sort(Comparator.comparing(f -> f.key));
      return fs;
    }

    public List<Feature> nearestNetworks(Coordinate p, int count) {
      if (networks.isEmpty()) return List.of();
      Point query = com.heatroute.geo.Metric.point(p);
      ItemDistance distance =
          (a, b) -> {
            Object x = a.getItem(), y = b.getItem();
            Geometry gx = x instanceof Feature ? ((Feature) x).geometry() : (Geometry) x,
                gy = y instanceof Feature ? ((Feature) y).geometry() : (Geometry) y;
            return gx.distance(gy);
          };
      Object[] objects =
          networkIndex.nearestNeighbour(
              query.getEnvelopeInternal(), query, distance, Math.min(count, networks.size()));
      List<Feature> found = new ArrayList<>();
      for (Object o : objects) found.add((Feature) o);
      found.sort(
          Comparator.comparingDouble((Feature f) -> f.geometry().distance(query))
              .thenComparing(f -> f.key));
      return found;
    }

    @SuppressWarnings("unchecked")
    public List<Feature> nearby(Envelope e) {
      List<Feature> list = new ArrayList<>(index.query(e));
      list.sort(Comparator.comparing(f -> f.key));
      return list;
    }
  }

  public static final class Root {
    public final Coordinate point;
    public final Feature existingChamber;
    public final int existingDn, degree;
    public final Set<String> incident;

    public Root(Coordinate p, Feature ch, int dn, int degree, Set<String> inc) {
      point = p;
      existingChamber = ch;
      existingDn = dn;
      this.degree = degree;
      incident = inc;
    }

    public String key() {
      return (existingChamber == null ? "new" : "old:" + existingChamber.key)
          + ":"
          + point.x
          + ":"
          + point.y;
    }
  }

  public static final class Branch {
    public final Feature target;
    public final LineString line;
    public final int dn;

    public Branch(Feature f, LineString l, int d) {
      target = f;
      line = l;
      dn = d;
    }
  }

  public static final class Component {
    public final Root root;
    public final Coordinate hub;
    public final LineString trunk;
    public final int trunkDn;
    public final List<Branch> branches;

    public Component(Root r, Coordinate h, LineString t, int d, List<Branch> b) {
      root = r;
      hub = h;
      trunk = t;
      trunkDn = d;
      branches = List.copyOf(b);
    }
  }

  public static final class Variant {
    public final List<Component> components = new ArrayList<>();
    public final List<Feature> missing = new ArrayList<>();
    public BigDecimal pipeCost = BigDecimal.ZERO,
        chamberCost = BigDecimal.ZERO,
        tieCost = BigDecimal.ZERO,
        penalty = BigDecimal.ZERO,
        length = BigDecimal.ZERO,
        score = BigDecimal.ZERO;
    public int tieCount;
    public String fingerprint;
    public final Map<String, Object> diagnostics = new LinkedHashMap<>();

    public BigDecimal construction() {
      return pipeCost.add(chamberCost).add(tieCost);
    }

    public BigDecimal total() {
      return construction().add(penalty);
    }
  }

  private Model() {}
}
