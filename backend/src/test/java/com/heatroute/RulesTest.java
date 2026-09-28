package com.heatroute;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.*;
import com.heatroute.constraints.ConstraintEngine;
import com.heatroute.constraints.ConstraintEngine.Span;
import com.heatroute.cost.CostFunction;
import com.heatroute.domain.Model;
import com.heatroute.domain.Model.*;
import com.heatroute.geo.Metric;
import com.heatroute.hydraulic.*;
import com.heatroute.ingest.GeoJsonInput;
import com.heatroute.tiein.TieInPlanner;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;

class RulesTest {
  Coordinate p(double x, double y) {
    return new Coordinate(x, y);
  }

  LineString line(double x, double y, double a, double b) {
    return Metric.line(p(x, y), p(a, b));
  }

  Feature obstacle(String id, String type, Geometry g) {
    return new Feature(TextNode.valueOf(id), "restriction", type, g, 0, BigDecimal.ZERO);
  }

  Polygon box(double x, double y, double a, double b) {
    return Metric.GF.createPolygon(new Coordinate[] {p(x, y), p(a, y), p(a, b), p(x, b), p(x, y)});
  }

  Feature target(String id, double x, double y, String flow) {
    return new Feature(
        TextNode.valueOf(id),
        "oks_connection_point",
        "",
        Metric.point(p(x, y)),
        0,
        new BigDecimal(flow));
  }

  ConstraintEngine rules(Feature... fs) {
    return new ConstraintEngine(new Snapshot(Arrays.asList(fs)));
  }

  @Test
  void exactMoneyAndScore() {
    assertThat(CostFunction.pipe(100, 100, 1)).isEqualByComparingTo("8974800");
    assertThat(CostFunction.score(new BigDecimal("13974800"), new BigDecimal("100")))
        .isEqualByComparingTo("0.6912944");
    assertThat(CostFunction.penalty(new BigDecimal("40"))).isEqualByComparingTo("120000000");
  }

  @Test
  void diameterUsesFlowAndLength() {
    assertThat(DiameterCatalog.select(new BigDecimal("3.5"), 181, 0).dn).isEqualTo(50);
    assertThat(DiameterCatalog.select(new BigDecimal("3.5"), 181.01, 0).dn).isEqualTo(65);
    assertThat(DiameterCatalog.select(new BigDecimal("40.2"), 554, 0).dn).isEqualTo(125);
    assertThat(DiameterCatalog.select(new BigDecimal("40.2001"), 10, 0).dn).isEqualTo(150);
  }

  @Test
  void continuousPathDoesNotResetAtChamber() {
    var r = new Root(p(0, 0), null, 50, 2, Set.of());
    var t = target("cp", 200, 0, "1");
    var c =
        new TreeHydraulics()
            .size(
                r, p(100, 0), line(0, 0, 100, 0), List.of(new Branch(t, line(100, 0, 200, 0), 50)));
    assertThat(c.trunkDn).isEqualTo(65);
    assertThat(c.branches.get(0).dn).isEqualTo(65);
  }

  @Test
  void parallelLeavesAreNotSummedForLength() {
    var r = new Root(p(0, 0), null, 50, 2, Set.of());
    var c =
        new TreeHydraulics()
            .size(
                r,
                p(10, 0),
                line(0, 0, 10, 0),
                List.of(
                    new Branch(target("a", 110, 0, "1"), line(10, 0, 110, 0), 50),
                    new Branch(target("b", 10, 100, "1"), line(10, 0, 10, 100), 50)));
    assertThat(c.trunkDn).isEqualTo(50);
  }

  @Test
  void maximumCoefficientAndAllSetBoundaries() {
    var c = rules();
    var pieces =
        c.partition(
            line(0, 0, 20, 0), List.of(new Span(2, 12, 1.6, "road"), new Span(5, 15, 1.25, "gas")));
    assertThat(pieces).hasSize(5);
    assertThat(pieces.get(2).factor).isEqualTo(1.6);
    assertThat(pieces.get(2).key).isEqualTo("gas,road");
    assertThat(pieces.stream().mapToDouble(s -> s.b - s.a).sum()).isEqualTo(20);
  }

  @Test
  void roadEntryAngleAndExtensions() {
    var c = rules(obstacle("road", "road", box(-2, -100, 2, 100)));
    assertThat(c.assess(line(-10, 0, 10, 0), 100, Set.of(), null).valid()).isTrue();
    assertThat(c.assess(line(-10, -30, 10, 30), 100, Set.of(), null).error).startsWith("ANGLE");
    assertThat(c.assess(line(-3, 0, 10, 0), 100, Set.of(), null).error)
        .startsWith("SPECIAL_EXTENSION");
  }

  @Test
  void gasTransverseCrossingAndParallelClearance() {
    var c = rules(obstacle("gas", "gas_pipeline", line(0, -20, 0, 20)));
    var ok = c.assess(line(-10, 0, 10, 0), 125, Set.of(), null);
    assertThat(ok.error).isNull();
    assertThat(ok.spans.get(0).b - ok.spans.get(0).a).isEqualTo(4);
    assertThat(c.assess(line(1, -10, 1, 10), 125, Set.of(), null).valid()).isFalse();
  }

  @Test
  void railwayNeverCrossed() {
    assertThat(
            rules(obstacle("rail", "railway", line(0, -10, 0, 10)))
                .assess(line(-10, 0, 10, 0), 50, Set.of(), null)
                .valid())
        .isFalse();
  }

  @Test
  void defaultEntryAllowsAdmissibleBoundary() {
    String previousPolicy = System.getProperty("heatroute.entryPolicy");
    System.clearProperty("heatroute.entryPolicy");
    try {
      assertThat(com.heatroute.constraints.EntryPolicy.name()).isEqualTo("cost");
      var own = obstacle("house", "oks", box(0, 0, 20, 20));
      var cp = target("cp", 2, 10, "1");
      var c = rules(own, cp);
      assertThat(c.assess(line(-10, 10, 2, 10), 50, Set.of(), cp).valid()).isTrue();
      assertThat(c.assess(line(30, 10, 2, 10), 50, Set.of(), cp).valid()).isTrue();
    } finally {
      if (previousPolicy == null) System.clearProperty("heatroute.entryPolicy");
      else System.setProperty("heatroute.entryPolicy", previousPolicy);
    }
  }

  @Test
  void ownBuildingAllowsCostSelectedStraightEntry() {
    String previousPolicy = System.getProperty("heatroute.entryPolicy");
    System.setProperty("heatroute.entryPolicy", "cost");
    try {
    var own = obstacle("house", "oks", box(0, 0, 20, 20));
    var cp = target("cp", 2, 10, "1");
    var c = rules(own, cp);
    assertThat(c.assess(line(-10, 10, 2, 10), 50, Set.of(), cp).valid()).isTrue();
    assertThat(c.assess(line(-10, 5, 2, 10), 50, Set.of(), cp).valid()).isTrue();
    assertThat(c.assess(line(30, 10, 2, 10), 50, Set.of(), cp).valid()).isTrue();
    assertThat(c.assess(line(1, 10, 2, 10), 50, Set.of(), cp).valid()).isFalse();
    assertThat(c.assess(Metric.line(p(-10, 5),p(1, 5),p(2,10)), 50, Set.of(), cp).valid()).isFalse();
    System.setProperty("heatroute.entryPolicy", "nearest");
    try {
      assertThat(c.assess(line(-10, 5, 2, 10), 50, Set.of(), cp).valid()).isFalse();
    } finally { System.clearProperty("heatroute.entryPolicy"); }
    assertThat(c.assess(line(-10, 10, 30, 10), 50, Set.of(), null).valid()).isFalse();
      } finally {
      if (previousPolicy == null) System.clearProperty("heatroute.entryPolicy");
      else System.setProperty("heatroute.entryPolicy", previousPolicy);
    }
  }

  @Test
  void routerChoosesCheaperNonNearestFacade() {
    String previousPolicy = System.getProperty("heatroute.entryPolicy");
    System.setProperty("heatroute.entryPolicy", "cost");
    try {
    Feature own = obstacle("house", "oks", box(0,0,20,20));
    Feature cp = target("cp", 2,10,"1");
    Snapshot snap = new Snapshot(List.of(own,cp));
    var engine = new ConstraintEngine(snap);
    var router = new com.heatroute.routing.VisibilityRouter(snap,engine);
    var result = router.route(p(40,10), p(2,10), 50, Set.of(), cp);
    assertThat(result).isNotNull();
    assertThat(result.getLength()).isEqualTo(38);
    assertThat(engine.assess(result,50,Set.of(),cp).valid()).isTrue();
    // A straight connection realizes the Euclidean lower cost bound in this fixture.
    assertThat(CostFunction.pipe(result.getLength(),50,1))
        .isEqualByComparingTo(CostFunction.pipe(p(40,10).distance(p(2,10)),50,1));
    assertThat(result.intersection(own.geometry().getBoundary()).getCoordinate().x).isEqualTo(20);
      } finally {
      if (previousPolicy == null) System.clearProperty("heatroute.entryPolicy");
      else System.setProperty("heatroute.entryPolicy", previousPolicy);
    }
  }

  @Test
  void portalSearchPrefersCheaperDetourToExpensiveStraightCrossing() {
    String previousPolicy = System.getProperty("heatroute.entryPolicy");
    System.setProperty("heatroute.entryPolicy", "cost");
    try {
    Feature own = obstacle("house", "oks", box(0,0,20,20));
    Feature tram = obstacle("tram", "tram_tracks", box(-40,5,-5,15));
    Feature cp = target("cp",10,10,"1");
    Snapshot snap = new Snapshot(List.of(own,tram,cp));
    var engine = new ConstraintEngine(snap);
    var router = new com.heatroute.routing.VisibilityRouter(snap,engine);
    var direct = line(-50,10,10,10);
    assertThat(engine.assess(direct,50,Set.of(),cp).valid()).isTrue();
    var result = router.route(p(-50,10),p(10,10),50,Set.of(),cp);
    assertThat(result).isNotNull();
    assertThat(engine.assess(result,50,Set.of(),cp).valid()).isTrue();
    java.util.function.Function<LineString,BigDecimal> price = route -> {
      BigDecimal cost = BigDecimal.ZERO;
      for (var part : engine.partition(route,engine.assess(route,50,Set.of(),cp).spans))
        cost=cost.add(CostFunction.pipe(part.b-part.a,50,part.factor));
      return cost;
    };
    assertThat(result.getLength()).isGreaterThan(direct.getLength());
    assertThat(price.apply(result)).isLessThan(price.apply(direct));
      } finally {
      if (previousPolicy == null) System.clearProperty("heatroute.entryPolicy");
      else System.setProperty("heatroute.entryPolicy", previousPolicy);
    }
  }

  @Test
  void selectedEntryStillRejectsReentryAndOtherBuildings() {
    Feature own = obstacle("own", "oks", Metric.GF.createMultiPolygon(
        new Polygon[]{box(0,0,10,10),box(20,0,30,10)}));
    Feature cp = target("cp", 2, 5, "1");
    assertThat(rules(own,cp).assess(line(40,5,2,5),50,Set.of(),cp).valid()).isFalse();
    Feature other = obstacle("other", "oks", box(-20,0,-15,10));
    assertThat(rules(own,cp,other).assess(line(-30,5,2,5),50,Set.of(),cp).valid()).isFalse();
  }

  @Test
  void throughLineOccupiesTwoCameraPorts() {
    Feature net =
        new Feature(
            TextNode.valueOf("net"), "heat_network", "", line(-10, 0, 10, 0), 500, BigDecimal.ZERO);
    var root = new TieInPlanner(new Snapshot(List.of(net))).at(p(0, 0), null);
    assertThat(root.degree).isEqualTo(2);
    assertThat(root.existingDn).isEqualTo(500);
    assertThat(CostFunction.chamber(root.existingDn)).isEqualByComparingTo("5000000");
  }

  @Test
  void idTypeIsNotCoerced() {
    assertThat(Model.key(TextNode.valueOf("12"))).isNotEqualTo(Model.key(IntNode.valueOf(12)));
  }

  @Test
  void metricRoundTrip() {
    var g = Metric.point(p(37.6, 55.75));
    var metric = Metric.transform(g, true);
    assertThat(metric.getSRID()).isEqualTo(32637);
    assertThat(metric.getCoordinate().x).isBetween(400000., 500000.);
    assertThat(Metric.transform(metric, false).distance(g)).isLessThan(1e-8);
  }

  @Test
  void directionChangesAtMostNinety() {
    assertThat(Metric.turn(p(0, 0), p(1, 0), p(1, 1))).isTrue();
    assertThat(Metric.turn(p(0, 0), p(1, 0), p(0, 1))).isFalse();
  }

  @Test
  void duplicateInputIdsAreRejected() {
    String f =
        "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]},\"properties\":{\"id\":1,\"object_type\":\"source\"}}";
    String json = "{\"type\":\"FeatureCollection\",\"features\":[" + f + "," + f + "]}";
    assertThatThrownBy(
            () ->
                new GeoJsonInput()
                    .stream(
                        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), x -> {}))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Duplicate");
  }

  @Test
  void diskSpoolPreservesGeometryAndCleansUp() throws Exception {
    var path = java.nio.file.Files.createTempFile("optimus-geometry-test", ".spool");
    try (var store = new com.heatroute.ingest.GeometryStore(path)) {
      var original = obstacle("a", "oks", box(0, 0, 20, 20));
      var disk = original.spooled(store);
      assertThat(disk.geometry().equalsExact(original.geometry())).isTrue();
      assertThat(disk.geometry().getSRID()).isEqualTo(32637);
    }
    assertThat(java.nio.file.Files.exists(path)).isFalse();
  }

  @Test
  void narrowEntryCertificateChecksDirectTieAndNearestPointUniqueness() {
    Feature owner =
        obstacle(
            "owner",
            "oks",
            Metric.GF.createMultiPolygon(new Polygon[] {box(0, 0, 3, 10), box(-4, 0, -2, 10)}));
    Feature cp = target("cp", 1, 5, "1");
    Feature network =
        new Feature(
            TextNode.valueOf("net"), "heat_network", "", line(20, 0, 20, 10), 100, BigDecimal.ZERO);
    var snapshot = new Snapshot(List.of(owner, cp, network));
    assertThat(com.heatroute.application.EntryCertificate.prove(snapshot, owner, cp, p(0, 5), 5.2))
        .isNotNull();
    Feature directTie =
        new Feature(
            TextNode.valueOf("tie"), "heat_network", "", line(-1, 0, -1, 10), 100, BigDecimal.ZERO);
    assertThat(
            com.heatroute.application.EntryCertificate.prove(
                new Snapshot(List.of(owner, cp, directTie)), owner, cp, p(0, 5), 5.2))
        .isNull();
    Feature equal = target("equal", 1.5, 5, "1");
    assertThat(
            com.heatroute.application.EntryCertificate.prove(snapshot, owner, equal, p(0, 5), 5.2))
        .isNull();
  }
}
