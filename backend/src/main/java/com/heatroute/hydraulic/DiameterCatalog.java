package com.heatroute.hydraulic;

import java.math.BigDecimal;
import java.util.*;

public final class DiameterCatalog {
  public static final class Row {
    public final int dn;
    public final BigDecimal capacity, rate;
    public final double maxLength, width, height;

    Row(int d, String c, double l, int r, double w, double h) {
      dn = d;
      capacity = new BigDecimal(c);
      maxLength = l;
      rate = BigDecimal.valueOf(r);
      width = w;
      height = h;
    }
  }

  public static final List<Row> ALL =
      List.of(
          new Row(50, "3.5", 181, 74023, .400, .125),
          new Row(65, "8.3", 245, 78631, .430, .140),
          new Row(80, "13.2", 327, 83530, .470, .160),
          new Row(100, "22.3", 419, 89748, .510, .180),
          new Row(125, "40.2", 554, 97275, .600, .225),
          new Row(150, "65.1", 696, 105507, .650, .250),
          new Row(200, "152.3", 1042, 120275, .880, .315),
          new Row(250, "274.9", 1379, 135323, 1.050, .400),
          new Row(300, "437.4", 1718, 150022, 1.150, .450),
          new Row(400, "943.1", 2477, 190299, 1.370, .560),
          new Row(500, "1663.4", 3245, 224137, 1.670, .710),
          new Row(600, "2627.7", 4037, 264790, 1.850, .800),
          new Row(700, "3735.1", 4775, 324298, 2.050, .900),
          new Row(800, "5296.8", 5644, 325996, 2.250, 1),
          new Row(900, "7165", 6518, 327693, 2.450, 1.1),
          new Row(1000, "9391.8", 7419, 418777, 2.650, 1.2),
          new Row(1200, "15012.8", 9288, 428074, 3.100, 1.425),
          new Row(1400, "22501.9", 11276, 683417, 3.450, 1.6));

  public static Row get(int dn) {
    return ALL.stream()
        .filter(r -> r.dn == dn)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown DN " + dn));
  }

  public static Row select(BigDecimal flow, double length, int downstreamDn) {
    if (flow.signum() < 0) throw new IllegalArgumentException("Negative flow");
    return ALL.stream()
        .filter(
            r ->
                r.capacity.compareTo(flow) >= 0
                    && r.maxLength + 1e-7 >= length
                    && r.dn >= downstreamDn)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No diameter satisfies flow/length"));
  }

  private DiameterCatalog() {}
}
