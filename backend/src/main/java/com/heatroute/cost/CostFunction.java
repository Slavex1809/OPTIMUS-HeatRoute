package com.heatroute.cost;

import com.heatroute.hydraulic.DiameterCatalog;
import java.math.BigDecimal;

public final class CostFunction {
  public static BigDecimal pipe(double metres, int dn, double factor) {
    return BigDecimal.valueOf(metres)
        .multiply(DiameterCatalog.get(dn).rate)
        .multiply(BigDecimal.valueOf(factor));
  }

  public static BigDecimal chamber(int dn) {
    DiameterCatalog.get(dn);
    return BigDecimal.valueOf(
        dn <= 200 ? 3000000 : dn <= 500 ? 5000000 : dn <= 1000 ? 8000000 : 12000000);
  }

  public static BigDecimal tie() {
    return new BigDecimal("5000000");
  }

  public static BigDecimal penalty(BigDecimal flow) {
    return new BigDecimal("100000000").add(flow.multiply(new BigDecimal("500000")));
  }

  public static BigDecimal score(BigDecimal cost, BigDecimal length) {
    return cost.multiply(new BigDecimal("0.7"))
        .divide(new BigDecimal("25000000"))
        .add(length.multiply(new BigDecimal("0.3")).divide(new BigDecimal("100")));
  }

  private CostFunction() {}
}
