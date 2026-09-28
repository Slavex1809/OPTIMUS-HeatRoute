package com.heatroute.geo;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.proj4j.*;

public final class Metric {
  public static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 32637);
  public static final double EPS = 1e-6;
  private static final ThreadLocal<CoordinateTransform[]> TRANSFORMS =
      ThreadLocal.withInitial(
          () -> {
            CRSFactory f = new CRSFactory();
            CoordinateReferenceSystem
                a = f.createFromParameters("WGS84", "+proj=longlat +datum=WGS84 +no_defs"),
                b =
                    f.createFromParameters(
                        "EPSG:32637", "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs");
            CoordinateTransformFactory t = new CoordinateTransformFactory();
            return new CoordinateTransform[] {t.createTransform(a, b), t.createTransform(b, a)};
          });

  public static Geometry transform(Geometry source, boolean forward) {
    Geometry g = source.copy();
    g.apply(
        (CoordinateFilter)
            c -> {
              ProjCoordinate q = new ProjCoordinate();
              TRANSFORMS.get()[forward ? 0 : 1].transform(new ProjCoordinate(c.x, c.y), q);
              c.x = q.x;
              c.y = q.y;
            });
    g.geometryChanged();
    g.setSRID(forward ? 32637 : 4326);
    return g;
  }

  public static Point point(Coordinate c) {
    return GF.createPoint(c.copy());
  }

  public static LineString line(Coordinate... c) {
    return GF.createLineString(c);
  }

  public static LineString part(LineString l, double a, double b) {
    return (LineString) new LengthIndexedLine(l).extractLine(a, b);
  }

  public static boolean straight(LineString l) {
    return l.getLength() <= l.getStartPoint().distance(l.getEndPoint()) + 1e-5;
  }

  public static boolean turn(Coordinate a, Coordinate b, Coordinate c) {
    return (b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y) >= -1e-7;
  }

  public static boolean turns(LineString l) {
    for (int i = 1; i < l.getNumPoints() - 1; i++)
      if (!turn(l.getCoordinateN(i - 1), l.getCoordinateN(i), l.getCoordinateN(i + 1)))
        return false;
    return true;
  }

  public static String key(Coordinate c) {
    return Double.toHexString(c.x) + ":" + Double.toHexString(c.y);
  }

  private Metric() {}
}
