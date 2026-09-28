package com.heatroute.application;

import com.heatroute.constraints.ConstraintEngine;
import com.heatroute.constraints.EntryPolicy;
import com.heatroute.domain.Model.*;
import com.heatroute.geo.Metric;
import com.heatroute.hydraulic.DiameterCatalog;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.operation.distance.DistanceOp;

/** Explanations are observations, not unjustified infeasibility certificates. */
public final class TargetDiagnostics {
  public static List<Map<String, Object>> describe(Snapshot s, Variant selected) {
    Set<String> missing = new HashSet<>();
    selected.missing.forEach(f -> missing.add(f.key));
    List<Map<String, Object>> out = new ArrayList<>();
    for (Feature target : s.targets) {
      Map<String, Object> d = new LinkedHashMap<>();
      d.put("targetId", target.id);
      d.put("flowTph", target.flow);
      d.put("status", missing.contains(target.key) ? "NO_ROUTE_FOUND" : "CONNECTED");
      d.put("infeasibilityProven", false);
      d.put("entryPolicy", EntryPolicy.name());
      for (Component c : selected.components) for (Branch branch : c.branches)
        if (branch.target.key.equals(target.key)) {
          Coordinate[] coords = branch.line.getCoordinates();
          Geometry leg = Metric.line(coords[coords.length - 2], coords[coords.length - 1]);
          for (Feature own : s.nearby(target.envelope))
            if (own.restriction.equals("oks") && own.geometry().covers(target.geometry())) {
              Geometry crossing = leg.intersection(own.geometry().getBoundary());
              d.put("selectedEntryWgs84", Metric.transform(crossing, false).toText());
              d.put("finalStraightLengthM", leg.getLength());
              d.put("internalEntryLengthM", leg.intersection(own.geometry()).getLength());
            }
        }
      double lower =
          s.nearestNetworks(target.geometry().getCoordinate(), 1).stream()
              .mapToDouble(f -> f.geometry().distance(target.geometry()))
              .min()
              .orElse(0);
      d.put("distanceLowerBoundM", lower);
      try {
        int dn = DiameterCatalog.select(target.flow, 0, 0).dn;
        d.put("diameterLowerBound", dn);
        for (Feature owner : s.nearby(target.geometry().getEnvelopeInternal()))
          if (owner.restriction.equals("oks") && owner.geometry().getDimension()==2 && owner.geometry().covers(target.geometry())) {
            d.put("ownBuildingId", owner.id);
            Coordinate end = target.geometry().getCoordinate(),
                boundary =
                    DistanceOp.nearestPoints(owner.geometry().getBoundary(), target.geometry())[0];
            Point boundaryPoint = Metric.point(boundary);
            Coordinate wgs = Metric.transform(boundaryPoint, false).getCoordinate();
            d.put("nearestBoundaryWgs84", List.of(wgs.x, wgs.y));
            boolean courtyard = false;
            for (int k = 0; k < owner.geometry().getNumGeometries(); k++) {
              Polygon poly = (Polygon) owner.geometry().getGeometryN(k);
              for (int j = 0; j < poly.getNumInteriorRing(); j++)
                if (poly.getInteriorRingN(j).distance(boundaryPoint) < 1e-6) courtyard = true;
            }
            d.put("nearestEntryFromCourtyard", courtyard);
            double len = boundary.distance(end);
            if (len > 1e-8) {
              double
                  clearance = ConstraintEngine.gap("oks", dn) + DiameterCatalog.get(dn).width / 2,
                  extension = clearance + .12;
              var certificate = EntryCertificate.prove(s, owner, target, boundary, clearance);
              if (EntryPolicy.nearestOnly() && certificate != null && missing.contains(target.key)) {
                d.put("entryCertificate", certificate);
                d.put("infeasibilityProven", true);
                d.put("reason", "UNIQUE_NEAREST_STRAIGHT_ENTRY_BLOCKED");
              }
              Coordinate a =
                  new Coordinate(
                      boundary.x + (boundary.x - end.x) / len * extension,
                      boundary.y + (boundary.y - end.y) / len * extension);
              int tries = 0;
              while (owner.geometry().distance(Metric.point(a)) < clearance + .02 && tries++ < 30) {
                extension += Math.max(.25, clearance / 4);
                a =
                    new Coordinate(
                        boundary.x + (boundary.x - end.x) / len * extension,
                        boundary.y + (boundary.y - end.y) / len * extension);
              }
              var check = new ConstraintEngine(s).assess(Metric.line(a, end), dn, Set.of(), target);
              d.put("nearestBoundaryApproachCheck", check.valid() ? "PASS" : check.error);
              d.put("approachClearanceM", owner.geometry().distance(Metric.point(a)));
            }
            break;
          }
      } catch (IllegalArgumentException e) {
        d.put("reason", "NO_CATALOG_DIAMETER_FOR_LOWER_BOUND");
        d.put("infeasibilityProven", true);
      }
      if (missing.contains(target.key) && !d.containsKey("reason"))
        d.put(
            "reason",
            "No compatible route found within search budget; review geometry and search"
                + " diagnostics");
      out.add(d);
    }
    return out;
  }

  private TargetDiagnostics() {}
}
