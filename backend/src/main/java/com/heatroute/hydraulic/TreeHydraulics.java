package com.heatroute.hydraulic;

import com.heatroute.domain.Model.*;
import java.math.BigDecimal;
import java.util.*;
import org.locationtech.jts.geom.*;

/** Exact minimum sizing for a star component, across chamber boundaries with equal DN. */
public final class TreeHydraulics {
  public Component size(Root root, Coordinate hub, LineString trunk, List<Branch> branches) {
    List<Branch> leaves = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    int largest = 50;
    for (Branch b : branches) {
      int dn = DiameterCatalog.select(b.target.flow, b.line.getLength(), 0).dn;
      leaves.add(new Branch(b.target, b.line, dn));
      largest = Math.max(largest, dn);
      total = total.add(b.target.flow);
    }
    if (trunk == null) return new Component(root, null, null, 0, leaves);
    for (int i = 0; i < leaves.size(); i++) {
      Branch b = leaves.get(i);
      if (b.target.flow.compareTo(total) == 0) {
        int dn = DiameterCatalog.select(total, b.line.getLength() + trunk.getLength(), largest).dn;
        leaves.set(i, new Branch(b.target, b.line, dn));
        largest = Math.max(largest, dn);
      }
    }
    for (var r : DiameterCatalog.ALL) {
      if (r.dn < largest || r.capacity.compareTo(total) < 0 || r.maxLength < trunk.getLength())
        continue;
      boolean valid = true;
      for (Branch b : leaves)
        if (b.dn == r.dn && b.line.getLength() + trunk.getLength() > r.maxLength + 1e-7)
          valid = false;
      if (valid) return new Component(root, hub, trunk, r.dn, leaves);
    }
    throw new IllegalArgumentException("No diameter for continuous shared paths");
  }
}
