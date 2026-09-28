package com.heatroute.application;

import com.heatroute.constraints.ConstraintEngine;
import com.heatroute.domain.Model.*;
import com.heatroute.geo.Metric;
import com.heatroute.hydraulic.TreeHydraulics;
import java.util.*;
import org.locationtech.jts.geom.*;

/** A separate final gate, independent of search ordering and cached acceptance decisions. */
public final class SolutionVerifier {
  private final Snapshot s;

  public SolutionVerifier(Snapshot s) {
    this.s = s;
  }

  public void verify(Variant v) {
    Set<String> assigned = new HashSet<>();
    ConstraintEngine rules = new ConstraintEngine(s);
    Map<String, Integer> tieCounts = new HashMap<>();
    List<LineString> all = new ArrayList<>();
    for (Component c : v.components) {
      Component expected = new TreeHydraulics().size(c.root, c.hub, c.trunk, c.branches);
      require(expected.trunkDn == c.trunkDn, "Trunk diameter not minimal");
      if (c.root.existingChamber != null) {
        int n = tieCounts.merge(c.root.existingChamber.key, 1, Integer::sum);
        require(c.root.degree + n <= 4, "Existing chamber degree");
      } else require(c.root.degree + 1 <= 4, "New root degree");
      if (c.trunk != null) {
        require(c.branches.size() + 1 <= 4, "Branch degree");
        require(
            c.trunk.getStartPoint().distance(Metric.point(c.root.point)) < 1e-6
                && c.trunk.getEndPoint().distance(Metric.point(c.hub)) < 1e-6,
            "Trunk endpoints");
        require(
            rules.assess(c.trunk, c.trunkDn, c.root.incident, null).valid(), "Trunk constraints");
        all.add(c.trunk);
      }
      for (int i = 0; i < c.branches.size(); i++) {
        Branch b = c.branches.get(i);
        if (c.trunk != null)
          require(
              Metric.turn(
                  c.trunk.getCoordinateN(c.trunk.getNumPoints() - 2),
                  c.hub,
                  b.line.getCoordinateN(1)),
              "Turn through chamber");
        require(assigned.add(b.target.key), "Duplicate target");
        require(b.dn == expected.branches.get(i).dn, "Leaf diameter not minimal");
        require(
            b.line.getStartPoint().distance(Metric.point(c.trunk == null ? c.root.point : c.hub))
                    < 1e-6
                && b.line.getEndPoint().distance(b.target.geometry()) < 1e-6,
            "Leaf endpoints");
        require(
            rules
                .assess(b.line, b.dn, c.trunk == null ? c.root.incident : Set.of(), b.target)
                .valid(),
            "Leaf constraints");
        all.add(b.line);
      }
    }
    for (int i = 0; i < all.size(); i++)
      for (int j = i + 1; j < all.size(); j++)
        require(Optimizer.onlySharedEndpoint(all.get(i), all.get(j)), "Unnoded intersection");
    for (Feature f : v.missing) require(assigned.add(f.key), "Duplicate missing");
    require(assigned.size() == s.targets.size(), "Targets lost");
    for (Feature f : s.targets) require(assigned.contains(f.key), "Target not accounted");
  }

  private void require(boolean ok, String detail) {
    if (!ok) throw new IllegalStateException("Verification failed: " + detail);
  }
}
