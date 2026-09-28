package com.heatroute.tiein;

import com.heatroute.domain.Model.*;
import com.heatroute.geo.Metric;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;

public final class TieInPlanner {
  private final Snapshot s;

  public TieInPlanner(Snapshot s) {
    this.s = s;
  }

  public Root at(Coordinate p, Feature chamber) {
    Set<String> incident = new TreeSet<>();
    int degree = 0, dn = 0;
    for (Feature f : s.nearby(new Envelope(p.x - .10, p.x + .10, p.y - .10, p.y + .10))) {
      if (!f.type.equals("heat_network")) continue;
      LineString l = (LineString) f.geometry();
      if (l.distance(Metric.point(p)) <= .10) {
        incident.add(f.key);
        dn = Math.max(dn, f.diameter);
        degree +=
            Math.min(
                        l.getStartPoint().distance(Metric.point(p)),
                        l.getEndPoint().distance(Metric.point(p)))
                    <= .10
                ? 1
                : 2;
      }
    }
    return new Root(p.copy(), chamber, dn, degree, incident);
  }

  public List<Root> candidates(Coordinate target) {
    Map<String, Root> roots = new TreeMap<>();
    List<Feature> lines = new ArrayList<>(s.nearestNetworks(target, 64));
    lines.sort(
        Comparator.comparingDouble((Feature f) -> f.geometry().distance(Metric.point(target)))
            .thenComparing(f -> f.key));
    for (Feature f : lines) {
      LineString l = (LineString) f.geometry();
      LengthIndexedLine indexed = new LengthIndexedLine(l);
      double pos = indexed.project(target);
      for (double at :
          new double[] {
            pos, Math.max(0, pos - 15), Math.min(l.getLength(), pos + 15), 0, l.getLength()
          }) {
        Coordinate p = indexed.extractPoint(at);
        for (Root r : candidatesAt(p)) roots.put(r.key(), r);
      }
    }
    List<Root> list = new ArrayList<>(roots.values());
    list.sort(
        Comparator.comparingDouble((Root r) -> r.point.distance(target)).thenComparing(Root::key));
    return list;
  }

  /** Resolve a proposed point using the same mandatory 10 m chamber rule. */
  public List<Root> candidatesAt(Coordinate p) {
    List<Root> roots = new ArrayList<>();
    for (Feature c : s.chambersNear(p, 10))
      if (c.geometry().getCoordinate().distance(p) <= 10) {
        Root r = at(c.geometry().getCoordinate(), c);
        if (r.degree + 1 <= 4 && !r.incident.isEmpty()) roots.add(r);
      }
    if (roots.isEmpty()) {
      Root r = at(p, null);
      if (r.degree + 1 <= 4 && !r.incident.isEmpty()) roots.add(r);
    }
    return roots;
  }
}
