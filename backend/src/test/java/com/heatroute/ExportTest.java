package com.heatroute;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.heatroute.application.*;
import com.heatroute.domain.Model;
import com.heatroute.domain.Model.*;
import com.heatroute.export.GeoJsonExporter;
import com.heatroute.geo.Metric;
import com.heatroute.ingest.GeoJsonInput;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;

class ExportTest {
  @Test
  void schemaReferencesCostsAndDeterminism() throws Exception {
    Coordinate a = new Coordinate(410000, 6170000),
        b = new Coordinate(410000, 6170200),
        t = new Coordinate(410050, 6170100);
    Feature net =
        new Feature(
            TextNode.valueOf("network"),
            "heat_network",
            "",
            Metric.line(a, b),
            500,
            BigDecimal.ZERO);
    Feature cp =
        new Feature(
            IntNode.valueOf(12),
            "oks_connection_point",
            "",
            Metric.point(t),
            0,
            new BigDecimal("20"));
    Snapshot s = new Snapshot(List.of(net, cp));
    var first = new Optimizer(s, x -> {}).solve();
    var second = new Optimizer(new Snapshot(List.of(cp, net)), x -> {}).solve();
    assertThat(first.variants.get(0).missing).isEmpty();
    assertThat(first.variants.get(0).fingerprint).isEqualTo(second.variants.get(0).fingerprint);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    new GeoJsonExporter().write(s, first.variants, bytes);
    JsonNode output = GeoJsonInput.JSON.readTree(bytes.toByteArray());
    Set<String> ids = new HashSet<>();
    Map<String, BigDecimal> pipes = new HashMap<>(),
        cameras = new HashMap<>(),
        lengths = new HashMap<>();
    List<JsonNode> lines = new ArrayList<>();
    for (JsonNode f : output.path("features")) {
      JsonNode p = f.path("properties");
      assertThat(ids.add(Model.key(p.path("id")))).isTrue();
      String v = p.path("variant_id").asText(), type = p.path("object_type").asText();
      assertThat(type).isIn("heat_network", "heat_chamber", "technical_node", "variant_summary");
      if (type.equals("heat_network")) {
        lines.add(p);
        pipes.merge(v, p.get("cost").decimalValue(), BigDecimal::add);
        lengths.merge(v, p.get("length").decimalValue(), BigDecimal::add);
        assertThat(p.get("depth_start").isNull()).isTrue();
      }
      if (type.equals("heat_chamber"))
        cameras.merge(v, p.get("cost").decimalValue(), BigDecimal::add);
    }
    ids.add(cp.key);
    for (JsonNode p : lines) {
      assertThat(ids).contains(Model.key(p.get("start_node_id")), Model.key(p.get("end_node_id")));
      if (p.get("end_node_id").isNumber())
        assertThat(p.get("end_node_id").intValue()).isEqualTo(12);
    }
    for (JsonNode f : output.path("features")) {
      JsonNode p = f.path("properties");
      if (!p.path("object_type").asText().equals("variant_summary")) continue;
      String v = p.path("variant_id").asText();
      assertThat(p.get("construction_cost").decimalValue())
          .isEqualByComparingTo(
              pipes
                  .get(v)
                  .add(cameras.get(v))
                  .add(p.get("existing_chamber_tie_in_cost").decimalValue()));
      assertThat(p.get("new_network_length").decimalValue()).isEqualByComparingTo(lengths.get(v));
      assertThat(p.get("existing_chamber_tie_in_count").asInt()).isZero();
      assertThat(p.get("chamber_construction_cost").decimalValue()).isEqualByComparingTo("5000000");
    }
  }
}
