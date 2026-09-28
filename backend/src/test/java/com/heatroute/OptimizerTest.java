package com.heatroute;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.TextNode;
import com.heatroute.application.*;
import com.heatroute.domain.Model.*;
import com.heatroute.geo.Metric;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

class OptimizerTest {
  private Feature point(String id, double x, double y) {
    return new Feature(
        TextNode.valueOf(id),
        "oks_connection_point",
        "",
        Metric.point(new Coordinate(x, y)),
        0,
        BigDecimal.ONE);
  }

  @Test
  void sharedChamberActuallySavesAndRecomputesFlow() {
    Feature net =
        new Feature(
            TextNode.valueOf("n"),
            "heat_network",
            "",
            Metric.line(new Coordinate(0, -100), new Coordinate(0, 100)),
            500,
            BigDecimal.ZERO);
    Snapshot s = new Snapshot(List.of(net, point("a", 100, 0), point("b", 100, 10)));
    var r = new Optimizer(s, p -> {}).solve();
    var best = r.variants.get(0);
    assertThat(best.missing).isEmpty();
    assertThat(best.components).hasSize(1);
    assertThat(best.components.get(0).trunk).isNotNull();
    assertThat(best.components.get(0).branches).hasSize(2);
    new SolutionVerifier(s).verify(best);
    @SuppressWarnings("unchecked")
    var explanation = (List<Map<String, Object>>) r.report.get("variantExplanations");
    assertThat((BigDecimal) explanation.get(0).get("constructionSaving")).isPositive();
    assertThat((BigDecimal) explanation.get(0).get("scoreImprovement")).isPositive();
  }

  @Test
  void prohibitivePenaltyNeverCausesIntentionalDisconnect() {
    Feature net =
        new Feature(
            TextNode.valueOf("n"),
            "heat_network",
            "",
            Metric.line(new Coordinate(0, -100), new Coordinate(0, 100)),
            1400,
            BigDecimal.ZERO);
    Snapshot s = new Snapshot(List.of(net, point("expensive", 1500, 0)));
    var r = new Optimizer(s, p -> {}).solve();
    assertThat(r.variants.get(0).missing).isEmpty();
    assertThat(r.variants.get(0).construction()).isGreaterThan(new BigDecimal("100500000"));
  }
}
