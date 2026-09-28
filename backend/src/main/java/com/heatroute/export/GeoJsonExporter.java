package com.heatroute.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.heatroute.constraints.ConstraintEngine;
import com.heatroute.cost.CostFunction;
import com.heatroute.domain.Model.*;
import com.heatroute.geo.Metric;
import com.heatroute.ingest.GeoJsonInput;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.locationtech.jts.linearref.LengthIndexedLine;

public final class GeoJsonExporter {
  public void write(Snapshot s, List<Variant> variants, OutputStream output) throws IOException {
    ConstraintEngine rules = new ConstraintEngine(s);
    try (JsonGenerator g = GeoJsonInput.JSON.getFactory().createGenerator(output)) {
      g.writeStartObject();
      g.writeStringField("type", "FeatureCollection");
      g.writeArrayFieldStart("features");
      int rank = 0;
      for (Variant v : variants) {
        String variant = "v" + (++rank);
        Ids ids = new Ids(s, variant);
        for (Component c : v.components) {
          JsonNode root = c.root.existingChamber == null ? ids.next() : c.root.existingChamber.id;
          int rootDn = c.root.existingDn;
          if (c.trunk != null) rootDn = Math.max(rootDn, c.trunkDn);
          else for (Branch b : c.branches) rootDn = Math.max(rootDn, b.dn);
          if (c.root.existingChamber == null) chamber(g, variant, root, c.root.point, rootDn);
          JsonNode origin = root;
          if (c.trunk != null) {
            JsonNode hub = ids.next();
            int hubDn = c.trunkDn;
            BigDecimal q = BigDecimal.ZERO;
            for (Branch b : c.branches) {
              hubDn = Math.max(hubDn, b.dn);
              q = q.add(b.target.flow);
            }
            chamber(g, variant, hub, c.hub, hubDn);
            line(g, variant, ids, c.trunk, c.trunkDn, q, root, hub, rules, c.root.incident, null);
            origin = hub;
          }
          for (Branch b : c.branches)
            line(
                g,
                variant,
                ids,
                b.line,
                b.dn,
                b.target.flow,
                origin,
                b.target.id,
                rules,
                c.trunk == null ? c.root.incident : Set.of(),
                b.target);
        }
        start(g, variant, ids.next(), "variant_summary", null);
        g.writeNumberField("rank", rank);
        g.writeNumberField("construction_cost", v.construction());
        g.writeNumberField("chamber_construction_cost", v.chamberCost);
        g.writeNumberField("existing_chamber_tie_in_count", v.tieCount);
        g.writeNumberField("existing_chamber_tie_in_cost", v.tieCost);
        g.writeNumberField("unconnected_penalty", v.penalty);
        g.writeNumberField("calculated_cost", v.total());
        g.writeNumberField("new_network_length", v.length);
        g.writeNumberField("score", v.score);
        g.writeArrayFieldStart("unconnected_oks_ids");
        for (Feature f : v.missing) g.writeTree(f.id);
        g.writeEndArray();
        g.writeStringField("fingerprint", v.fingerprint);
        end(g);
      }
      g.writeEndArray();
      g.writeEndObject();
    }
  }

  private void line(
      JsonGenerator g,
      String variant,
      Ids ids,
      LineString line,
      int dn,
      BigDecimal flow,
      JsonNode start,
      JsonNode finish,
      ConstraintEngine rules,
      Set<String> incident,
      Feature target)
      throws IOException {
    var check = rules.assess(line, dn, incident, target);
    if (!check.valid()) throw new IOException("Unverified exported line " + check.error);
    var parts = rules.partition(line, check.spans);
    JsonNode from = start;
    LengthIndexedLine indexed = new LengthIndexedLine(line);
    for (int i = 0; i < parts.size(); i++) {
      var p = parts.get(i);
      JsonNode to = i == parts.size() - 1 ? finish : ids.next();
      if (i < parts.size() - 1) {
        start(g, variant, to, "technical_node", Metric.point(indexed.extractPoint(p.b)));
        end(g);
      }
      start(g, variant, ids.next(), "heat_network", Metric.part(line, p.a, p.b));
      g.writeObjectField("start_node_id", from);
      g.writeObjectField("end_node_id", to);
      g.writeNumberField("flow_tph", flow);
      g.writeNumberField("diameter", dn);
      g.writeNumberField("length", p.b - p.a);
      g.writeStringField("laying_method", p.factor > 1 ? "special" : "base");
      g.writeNullField("depth_start");
      g.writeNullField("depth_end");
      g.writeNumberField("cost", CostFunction.pipe(p.b - p.a, dn, p.factor));
      g.writeNumberField("special_factor", p.factor);
      end(g);
      from = to;
    }
  }

  private void chamber(JsonGenerator g, String v, JsonNode id, Coordinate p, int dn)
      throws IOException {
    start(g, v, id, "heat_chamber", Metric.point(p));
    g.writeNumberField("diameter", dn);
    g.writeNumberField("cost", CostFunction.chamber(dn));
    end(g);
  }

  private void start(JsonGenerator g, String variant, JsonNode id, String type, Geometry geometry)
      throws IOException {
    g.writeStartObject();
    g.writeStringField("type", "Feature");
    g.writeFieldName("geometry");
    if (geometry == null) g.writeNull();
    else {
      GeoJsonWriter writer = new GeoJsonWriter(16);
      writer.setEncodeCRS(false);
      g.writeRawValue(writer.write(Metric.transform(geometry, false)));
    }
    g.writeObjectFieldStart("properties");
    g.writeObjectField("id", id);
    g.writeStringField("object_type", type);
    g.writeStringField("variant_id", variant);
  }

  private void end(JsonGenerator g) throws IOException {
    g.writeEndObject();
    g.writeEndObject();
  }

  private static final class Ids {
    final Snapshot s;
    final String prefix;
    int count;

    Ids(Snapshot s, String v) {
      this.s = s;
      prefix = "optimus_" + v + "_";
    }

    JsonNode next() {
      String id;
      do {
        id = prefix + (++count);
      } while (s.byId.containsKey("s:" + id));
      return TextNode.valueOf(id);
    }
  }

  public static final class Limited extends FilterOutputStream {
    private long count;
    private final long max;

    public Limited(OutputStream out, long max) {
      super(out);
      this.max = max;
    }

    private void add(int n) throws IOException {
      if (n > max - count) throw new IOException("Result exceeds byte limit");
      count += n;
    }

    public void write(int b) throws IOException {
      add(1);
      out.write(b);
    }

    public void write(byte[] b, int off, int len) throws IOException {
      add(len);
      out.write(b, off, len);
    }
  }
}
