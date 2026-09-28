package com.heatroute.routing;

import com.heatroute.constraints.ConstraintEngine;
import com.heatroute.domain.Model.*;
import com.heatroute.geo.Metric;
import com.heatroute.hydraulic.DiameterCatalog;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.operation.distance.DistanceOp;

/** Reverse terminal planning. Computes continuous outer-end intervals, not a fixed extension.
 * Intervals describe clearance from the own building only. Every emitted terminal segment
 * is separately checked against ALL constraints before routing may use it.
 */
public final class TerminalEntryPlanner {
  private final Snapshot snapshot;
  private final ConstraintEngine rules;
  private final Map<String, Plan> cache = new TreeMap<>();
  private final Map<String,List<Coordinate>> legalGateCache=new TreeMap<>();
  public TerminalEntryPlanner(Snapshot snapshot, ConstraintEngine rules) {
    this.snapshot=snapshot; this.rules=rules;
  }
  public static final class Plan {
    public Feature target, owner;
    public Coordinate boundary;
    public double ux,uy,insideLength,clearance,horizon;
    public final List<double[]> intervals=new ArrayList<>();
    public String status;
    public Coordinate point(double t) { return new Coordinate(boundary.x+ux*t,boundary.y+uy*t); }
  }
  public Plan plan(Feature target,int dn) {
    String key=target.key+"/"+dn;
    if(cache.containsKey(key))return cache.get(key);
    Plan p=new Plan();p.target=target;
    for(Feature f:snapshot.nearby(target.envelope))
      if(f.restriction.equals("oks") && f.geometry().getDimension()==2 && f.geometry().covers(target.geometry())) {p.owner=f;break;}
    if(p.owner==null){p.status="NO_OWN_POLYGON";cache.put(key,p);return p;}
    Coordinate end=target.geometry().getCoordinate();
    p.boundary=DistanceOp.nearestPoints(p.owner.geometry().getBoundary(),target.geometry())[0];
    p.insideLength=p.boundary.distance(end);
    if(p.insideLength<1e-8){p.status="TARGET_ON_BOUNDARY_REQUIRES_DIRECTION_SEARCH";cache.put(key,p);return p;}
    p.ux=(p.boundary.x-end.x)/p.insideLength;p.uy=(p.boundary.y-end.y)/p.insideLength;
    p.clearance=ConstraintEngine.gap("oks",dn)+DiameterCatalog.get(dn).width/2;
    p.horizon=Math.max(0,DiameterCatalog.get(dn).maxLength-p.insideLength);
    if(p.horizon<1e-8){p.status="DIAMETER_LENGTH_LIMIT";cache.put(key,p);return p;}
    Geometry cross=Metric.line(p.boundary,p.point(p.horizon)).intersection(p.owner.geometry());
    for(int k=0;k<cross.getNumGeometries();k++) {
      Geometry part=cross.getGeometryN(k);double lo=Double.POSITIVE_INFINITY,hi=0;
      for(Coordinate c:part.getCoordinates()) {double t=(c.x-p.boundary.x)*p.ux+(c.y-p.boundary.y)*p.uy;lo=Math.min(lo,t);hi=Math.max(hi,t);}
      if(hi<=1e-5)continue;
      if(lo<=1e-5 && part.getLength()>1e-4) p.horizon=0;
      else p.horizon=Math.min(p.horizon,lo);
    }
    List<double[]> blocked=new ArrayList<>();
    Geometry rings=p.owner.geometry().getBoundary();
    for(int k=0;k<rings.getNumGeometries();k++) {
      Coordinate[] cs=rings.getGeometryN(k).getCoordinates();
      for(int j=1;j<cs.length;j++) blockCapsule(p,cs[j-1],cs[j],blocked);
    }
    blocked.sort(Comparator.comparingDouble(x->x[0]));
    double cursor=0;
    for(double[] x:blocked) {
      if(x[0]>cursor+1e-7)p.intervals.add(new double[]{cursor,x[0]});
      cursor=Math.max(cursor,x[1]);
    }
    if(cursor<p.horizon-1e-7)p.intervals.add(new double[]{cursor,p.horizon});
    p.status=p.intervals.isEmpty()?"NO_OWN_CLEARANCE_INTERVAL":"OUTER_INTERVALS_FOUND";
    cache.put(key,p);return p;
  }
  private static void blockCapsule(Plan p,Coordinate a,Coordinate b,List<double[]> out) {
    double ex=b.x-a.x,ey=b.y-a.y,den=ex*ex+ey*ey;
    double wx=p.boundary.x-a.x,wy=p.boundary.y-a.y;
    double q0=den==0?0:(wx*ex+wy*ey)/den;
    double qv=den==0?0:(p.ux*ex+p.uy*ey)/den;
    TreeSet<Double> cuts=new TreeSet<>();cuts.add(0.0);cuts.add(p.horizon);
    if(Math.abs(qv)>1e-18)for(double t:new double[]{-q0/qv,(1-q0)/qv})if(t>0&&t<p.horizon)cuts.add(t);
    List<Double> points=new ArrayList<>(cuts);
    for(int i=1;i<points.size();i++) {
      double lo=points.get(i-1),hi=points.get(i),q=q0+qv*(lo+hi)/2;
      double x=wx,y=wy,dx=p.ux,dy=p.uy;
      if(q>=1){x-=ex;y-=ey;}
      else if(q>0){x-=q0*ex;y-=q0*ey;dx-=qv*ex;dy-=qv*ey;}
      double aa=dx*dx+dy*dy,bb=x*dx+y*dy,cc=x*x+y*y-p.clearance*p.clearance;
      if(aa<1e-18){
        if(Math.abs(bb)<1e-14){if(cc<0)out.add(new double[]{lo,hi});}
        else {double root=-cc/(2*bb);double l=bb>0?lo:Math.max(lo,root),h=bb>0?Math.min(hi,root):hi;if(h>l)out.add(new double[]{l,h});}
      } else {
        double disc=bb*bb-aa*cc;
        if(disc<=0)continue;
        double sq=Math.sqrt(disc),l=Math.max(lo,(-bb-sq)/aa),h=Math.min(hi,(-bb+sq)/aa);
        if(h>l)out.add(new double[]{l,h});
      }
    }
  }
  public List<Coordinate> legalGates(Feature target,int dn) {
    String key=target.key+"/"+dn;
    if(legalGateCache.containsKey(key))return legalGateCache.get(key);
    Plan p=plan(target,dn);List<Coordinate> gates=new ArrayList<>();
    if(p.boundary==null || p.insideLength<1e-8){legalGateCache.put(key,gates);return gates;}
    List<Double> projections=new ArrayList<>();
    for(Feature network:snapshot.nearestNetworks(target.geometry().getCoordinate(),8)) {
      Coordinate q=DistanceOp.nearestPoints(network.geometry(),target.geometry())[0];
      projections.add((q.x-p.boundary.x)*p.ux+(q.y-p.boundary.y)*p.uy);
    }
    for(double[] interval:p.intervals) {
      double lo=interval[0]+1e-4,hi=interval[1]-1e-4;if(hi<lo)continue;
      TreeSet<Double> ts=new TreeSet<>();ts.add(lo);ts.add(hi);ts.add((lo+hi)/2);
      for(double projection:projections)ts.add(Math.max(lo,Math.min(hi,projection)));
      for(double step:new double[]{.02,.12,.5,1,2,4,8,16,32,64,128})if(lo+step<hi)ts.add(lo+step);
      for(double t:ts) {
        Coordinate gate=p.point(t);
        if(p.owner.geometry().distance(Metric.point(gate))<p.clearance-1e-6)continue;
        if(rules.assess(Metric.line(gate,target.geometry().getCoordinate()),dn,Set.of(),target).valid())gates.add(gate);
      }
    }
    legalGateCache.put(key,gates);return gates;
  }
  public List<Coordinate> gates(Feature target,int dn,Coordinate source) {
    List<Coordinate> usable=new ArrayList<>();
    for(Coordinate gate:legalGates(target,dn))
      if(gate.distance(source)+gate.distance(target.geometry().getCoordinate())<=DiameterCatalog.get(dn).maxLength+1e-6)usable.add(gate);
    return usable;
  }
  public List<Map<String,Object>> diagnostics() {
    List<Map<String,Object>> rows=new ArrayList<>();
    for(Feature target:snapshot.targets) {
      int dn=DiameterCatalog.select(target.flow,0,0).dn;Plan p=plan(target,dn);Map<String,Object> d=new LinkedHashMap<>();
      d.put("targetId",target.id);d.put("diameterLowerBound",dn);d.put("status",p.status);
      if(p.owner!=null){d.put("ownBuildingId",p.owner.id);d.put("clearanceM",p.clearance);d.put("insideLengthM",p.insideLength);d.put("outwardRayLimitM",p.horizon);d.put("outerEndpointIntervalsM",p.intervals);}
      List<List<Double>> endpoints=new ArrayList<>();
      for(Coordinate gate:legalGates(target,dn)) {
        Coordinate wgs=Metric.transform(Metric.point(gate),false).getCoordinate();
        endpoints.add(List.of(wgs.x,wgs.y));
      }
      d.put("validatedOuterEndpointsWgs84",endpoints);
      d.put("validatedEndpointCount",endpoints.size());
      d.put("intervalScope","Continuous own-building clearance; emitted endpoints additionally pass all terminal-segment constraints; candidate sampling is finite");
      rows.add(d);
    }
    return rows;
  }
}
