package com.heatroute.application;

import com.heatroute.domain.Model.*;
import com.heatroute.geo.Metric;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;

/**
 * A sufficient, deliberately narrow impossibility test for the required final straight entry.
 * Between consecutive building intersections separated by D, distance to the building is at most
 * min(t,D-t), hence at most D/2. If D/2 < required clearance, no upstream bend can stand in this
 * interval. A tie directly in the interval is checked separately.
 */
public final class EntryCertificate {
  public static Map<String, Object> prove(
      Snapshot s, Feature owner, Feature target, Coordinate boundary, double clearance) {
    Coordinate end = target.geometry().getCoordinate();
    double nearest = end.distance(boundary);
    if (nearest < 1e-5) return null;
    Geometry rings = owner.geometry().getBoundary();
    for (int k = 0; k < rings.getNumGeometries(); k++) {
      Coordinate[] cs = rings.getGeometryN(k).getCoordinates();
      for (int i = 1; i < cs.length; i++) {
        Coordinate q = new LineSegment(cs[i - 1], cs[i]).closestPoint(end);
        if (Math.abs(q.distance(end) - nearest) < 1e-5 && q.distance(boundary) > 1e-4) return null;
      }
    }
    Envelope e = owner.envelope;
    double extent = Math.hypot(e.getWidth(), e.getHeight()) + 100;
    Coordinate far =
        new Coordinate(
            boundary.x + (boundary.x - end.x) / nearest * extent,
            boundary.y + (boundary.y - end.y) / nearest * extent);
    LineString ray = Metric.line(boundary, far);
    LengthIndexedLine index = new LengthIndexedLine(ray);
    Geometry intersections = ray.intersection(owner.geometry());
    double next = Double.POSITIVE_INFINITY;
    for (int k = 0; k < intersections.getNumGeometries(); k++) {
      Geometry part = intersections.getGeometryN(k);
      double lo = Double.POSITIVE_INFINITY, hi = 0;
      for (Coordinate c : part.getCoordinates()) {
        double t = index.project(c);
        lo = Math.min(lo, t);
        hi = Math.max(hi, t);
      }
      if (hi <= 1e-5) continue;
      if (lo <= 1e-5 && part.getLength() > 1e-4) next = 0;
      else next = Math.min(next, lo);
    }
    if (!Double.isFinite(next) || next / 2 >= clearance - .01) return null;
    LineString interval = Metric.line(boundary, index.extractPoint(Math.max(next, 1e-5)));
    Envelope query = interval.getEnvelopeInternal();
    query.expandBy(.10);
    for (Feature f : s.nearby(query))
      if (f.type.equals("heat_network") && f.geometry().distance(interval) <= .10) return null;
    return Map.of(
        "rule",
        "UNIQUE_NEAREST_STRAIGHT_ENTRY_BLOCKED",
        "buildingId",
        owner.id,
        "freeRayLengthM",
        next,
        "clearanceUpperBoundM",
        next / 2,
        "requiredClearanceM",
        clearance,
        "existingNetworkInEntryInterval",
        false,
        "argument",
        "Every possible upstream bend in the entry interval violates the own-building clearance; no"
            + " direct tie exists in that interval");
  }

  private EntryCertificate() {}
}
