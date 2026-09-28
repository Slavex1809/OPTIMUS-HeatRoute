package com.heatroute.constraints;

import com.heatroute.domain.Model.*;
import com.heatroute.geo.Metric;
import com.heatroute.hydraulic.DiameterCatalog;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.DistanceOp;

public final class ConstraintEngine {
  public static final class Span {
    public final double a, b, factor;
    public final String key;

    public Span(double a, double b, double k, String key) {
      this.a = a;
      this.b = b;
      factor = k;
      this.key = key;
    }
  }

  public static final class Check {
    public final List<Span> spans = new ArrayList<>();
    public String error;

    public boolean valid() {
      return error == null;
    }

    Check fail(String e) {
      error = e;
      return this;
    }
  }

  public static boolean forbidden(String type) {
    return Set.of(
            "oks", "park", "social_area", "prohibited_site", "water", "railway", "reserved_route")
        .contains(type);
  }

  public static double gap(String t, int dn) {
    return t.equals("reserved_route")
        ? .05
        : t.equals("oks")
            ? (dn < 500 ? 5 : dn <= 800 ? 7 : 9)
            : Set.of("road", "tram_tracks").contains(t)
                ? 1.5
                : Set.of("gas_pipeline", "power_cable").contains(t) ? 2 : 1;
  }

  public static double factor(String t) {
    switch (t) {
      case "road":
        return 1.60;
      case "tram_tracks":
        return 1.75;
      case "gas_pipeline":
        return 1.25;
      case "power_cable":
        return 1.15;
      case "heat_network":
        return 1.05;
      default:
        return 1;
    }
  }

  public static double halfOther(Feature f) {
    return f.type.equals("heat_network")
        ? DiameterCatalog.get(f.diameter).width / 2
        : f.restriction.equals("gas_pipeline") ? .2 : f.restriction.equals("power_cable") ? .1 : 0;
  }

  public final Snapshot snapshot;

  public ConstraintEngine(Snapshot s) {
    snapshot = s;
  }

  public Check assess(LineString line, int dn, Set<String> rootExceptions, Feature target) {
    Check out = new Check();
    if (line.isEmpty() || !line.isSimple() || !Metric.turns(line))
      return out.fail("GEOMETRY_OR_TURN");
    double length = line.getLength(), half = DiameterCatalog.get(dn).width / 2;
    LengthIndexedLine indexed = new LengthIndexedLine(line);
    Envelope box = line.getEnvelopeInternal();
    box.expandBy(13);
    for (Feature f : snapshot.nearby(box)) {
      String type = f.type.equals("heat_network") ? "heat_network" : f.restriction;
      if (!forbidden(type) && factor(type) == 1) continue;
      Geometry obstacle = f.geometry();
      double clearance = gap(type, dn) + half + halfOther(f);
      if (line.distance(obstacle) >= clearance - 1e-6) continue;
      if (forbidden(type)) {
        if (type.equals("oks")
            && target != null
            && obstacle.covers(target.geometry())
            && line.getEndPoint().distance(target.geometry()) < 1e-5) {
          Coordinate terminal = target.geometry().getCoordinate(),
              boundary = DistanceOp.nearestPoints(obstacle.getBoundary(), target.geometry())[0];
          LineString finalLeg = Metric.line(line.getCoordinateN(line.getNumPoints() - 2), terminal);
          if (EntryPolicy.nearestOnly() && finalLeg.distance(Metric.point(boundary)) > 1e-5)
            return out.fail("OWN_OKS_NEAREST_BOUNDARY:" + f.key);
          if (obstacle.contains(finalLeg.getStartPoint()))
            return out.fail("OWN_OKS_START_INSIDE:" + f.key);
          Geometry inside = finalLeg.intersection(obstacle);
          if (inside.getNumGeometries() != 1
              || inside.getDimension() != 1
              || inside.distance(target.geometry()) > 1e-6)
            return out.fail("OWN_OKS_REENTRY:" + f.key);
          if (line.getNumPoints() > 2) {
            Coordinate[] prefix = Arrays.copyOf(line.getCoordinates(), line.getNumPoints() - 1);
            if (Metric.line(prefix).distance(obstacle) < clearance - 1e-6)
              return out.fail("OWN_OKS_PREFIX:" + f.key);
          }
          continue;
        }
        return out.fail("CLEARANCE:" + f.key);
      }
      Geometry cross = line.intersection(obstacle);
      List<Span> spans = new ArrayList<>();
      if (rootExceptions.contains(f.key) && line.getStartPoint().distance(obstacle) < .15) {
        if (cross.getDimension() > 0 && !cross.isEmpty())
          return out.fail("ROOT_COLLINEAR:" + f.key);
        boolean other = false;
        for (Coordinate c : cross.getCoordinates())
          if (c.distance(line.getCoordinateN(0)) > .15) other = true;
        if (!other) {
          Geometry near = line.intersection(obstacle.buffer(clearance));
          boolean prefixOnly = true;
          for (int k = 0; k < near.getNumGeometries(); k++)
            if (near.getGeometryN(k).distance(line.getStartPoint()) > .15) prefixOnly = false;
          if (prefixOnly) continue;
        }
      }
      if (cross.isEmpty()) return out.fail("NEAR_WITHOUT_CROSSING:" + f.key);
      if (obstacle.getDimension() == 2) {
        for (int i = 0; i < cross.getNumGeometries(); i++) {
          Geometry part = cross.getGeometryN(i);
          if (!(part instanceof LineString) || part.getLength() < 1e-7)
            return out.fail("TANGENCY:" + f.key);
          Coordinate[] cc = part.getCoordinates();
          double a = indexed.project(cc[0]), b = indexed.project(cc[cc.length - 1]);
          if (a > b) {
            double x = a;
            a = b;
            b = x;
          }
          double extend = Set.of("road", "tram_tracks").contains(type) ? 3 : 2;
          if (a - extend < -1e-6 || b + extend > length + 1e-6)
            return out.fail("SPECIAL_EXTENSION:" + f.key);
          LineString special = Metric.part(line, a - extend, b + extend);
          if (!Metric.straight(special)) return out.fail("SPECIAL_NOT_STRAIGHT:" + f.key);
          if (Set.of("road", "tram_tracks").contains(type)
              && angle(special, obstacle.getBoundary(), indexed.extractPoint(a)) < 45 - 1e-7)
            return out.fail("ANGLE:" + f.key);
          spans.add(
              new Span(
                  Math.max(0, a - extend),
                  Math.min(length, b + extend),
                  factor(type),
                  f.key + "/" + i));
        }
      } else {
        if (cross.getDimension() > 0) return out.fail("COLLINEAR:" + f.key);
        for (int i = 0; i < cross.getNumGeometries(); i++) {
          Coordinate c = cross.getGeometryN(i).getCoordinate();
          double p = indexed.project(c),
              extend = Set.of("road", "tram_tracks").contains(type) ? 3 : 2;
          if (p - extend < -1e-6 || p + extend > length + 1e-6)
            return out.fail("SPECIAL_EXTENSION:" + f.key);
          LineString special = Metric.part(line, p - extend, p + extend);
          if (!Metric.straight(special)) return out.fail("SPECIAL_NOT_STRAIGHT:" + f.key);
          if (Set.of("road", "tram_tracks").contains(type)
              && angle(special, obstacle, c) < 45 - 1e-7) return out.fail("ANGLE:" + f.key);
          spans.add(
              new Span(
                  Math.max(0, p - extend),
                  Math.min(length, p + extend),
                  factor(type),
                  f.key + "/" + i));
        }
      }
      // Clearance governs passing alongside an object. A straight transverse crossing
      // must leave the full envelope, even when that envelope exceeds the tariff zone.
      Geometry near = line.intersection(obstacle.buffer(clearance));
      for (int i = 0; i < near.getNumGeometries(); i++) {
        Geometry piece = near.getGeometryN(i);
        if (piece.distance(cross) > 1e-6) return out.fail("NEAR_WITHOUT_CROSSING:" + f.key);
        if (piece instanceof LineString && !Metric.straight((LineString) piece))
          return out.fail("CROSSING_APPROACH_NOT_STRAIGHT:" + f.key);
      }
      out.spans.addAll(spans);
    }
    return out;
  }

  private double angle(LineString route, Geometry boundary, Coordinate at) {
    double result = 90;
    boolean found = false;
    Coordinate a = route.getCoordinateN(0), b = route.getCoordinateN(route.getNumPoints() - 1);
    double vx = b.x - a.x, vy = b.y - a.y;
    for (int k = 0; k < boundary.getNumGeometries(); k++) {
      Coordinate[] cs = boundary.getGeometryN(k).getCoordinates();
      for (int i = 1; i < cs.length; i++) {
        LineSegment segment = new LineSegment(cs[i - 1], cs[i]);
        if (segment.distance(at) > 1e-5) continue;
        double wx = cs[i].x - cs[i - 1].x,
            wy = cs[i].y - cs[i - 1].y,
            den = Math.hypot(vx, vy) * Math.hypot(wx, wy);
        if (den == 0) continue;
        double deg = Math.toDegrees(Math.acos(Math.min(1, Math.abs(vx * wx + vy * wy) / den)));
        result = Math.min(result, deg);
        found = true;
      }
    }
    return found ? result : 0;
  }

  public List<Span> partition(LineString line, List<Span> spans) {
    TreeSet<Double> cuts = new TreeSet<>();
    cuts.add(0.0);
    cuts.add(line.getLength());
    for (Span s : spans) {
      cuts.add(s.a);
      cuts.add(s.b);
    }
    List<Double> v = new ArrayList<>(cuts);
    List<Span> result = new ArrayList<>();
    for (int i = 1; i < v.size(); i++) {
      double a = v.get(i - 1), b = v.get(i);
      if (b - a < 1e-7) continue;
      double middle = (a + b) / 2, k = 1;
      TreeSet<String> active = new TreeSet<>();
      for (Span s : spans)
        if (middle > s.a && middle < s.b) {
          k = Math.max(k, s.factor);
          active.add(s.key);
        }
      result.add(new Span(a, b, k, String.join(",", active)));
    }
    return result;
  }
}
