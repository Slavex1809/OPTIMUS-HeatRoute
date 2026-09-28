package com.heatroute.application;

import com.heatroute.domain.Model.*;
import com.heatroute.geo.Metric;
import com.heatroute.hydraulic.TreeHydraulics;
import com.heatroute.tiein.TieInPlanner;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;

/** Monotone local improvement of a complete network; every move preserves its target set. */
public final class NetworkRefiner {
  private final Snapshot snapshot;
  private final Optimizer optimizer;
  private final TieInPlanner tie;
  private final TreeHydraulics hydraulic = new TreeHydraulics();
  private final Consumer<Map<String,Object>> progress;
  private final Map<Component,BigDecimal> scores = new IdentityHashMap<>();
  private final Map<String,List<Component>> groupCache = new HashMap<>();
  private long checks, accepted;
  private static final BigDecimal MIN_GAIN = new BigDecimal("0.000000001");

  public NetworkRefiner(Snapshot snapshot, Optimizer optimizer,
                        Consumer<Map<String,Object>> progress) {
    this.snapshot=snapshot; this.optimizer=optimizer; this.progress=progress;
    tie=new TieInPlanner(snapshot);
  }

  public Map<String,Object> refine(Variant network, int seed) {
    optimizer.score(network);
    BigDecimal before=network.score, costBefore=network.construction(), lengthBefore=network.length;
    Set<String> coverage=coverage(network);
    long started=System.nanoTime(), firstCheck=checks, firstAccepted=accepted;
    // Coverage-repair and beam-assembled candidates also receive economic merging.
    optimizer.merge(network);
    optimizer.score(network);
    for(int round=0; round<2; round++) {
      BigDecimal previous=network.score;
      List<Component> ordered=new ArrayList<>(network.components);
      ordered.sort(Comparator.comparing((Component c)->optimizer.group(c))
          .thenComparing(c->c.root.key()));
      int done=0;
      for(Component old:ordered) {
        List<Component> rest=new ArrayList<>(network.components); rest.remove(old);
        Component best=shortcuts(old,rest);
        best=retie(best,rest);
        best=moveHub(best,rest);
        best=bends(best,rest);
        best=shortcuts(best,rest);
        if(best!=old) {
          network.components.set(network.components.indexOf(old),best);
          accepted++;
        }
        progress.accept(Map.of("phase","REFINING","seed",seed,"round",round+1,
            "componentsDone",++done,"componentsTotal",ordered.size(),"checks",checks));
      }
      optimizer.score(network);
      if(round==0) repartition(network);
      optimizer.merge(network);
      optimizer.score(network);
      if(previous.subtract(network.score).compareTo(MIN_GAIN)<=0) break;
    }
    if(!coverage.equals(coverage(network))) throw new IllegalStateException("Refinement changed coverage");
    if(network.score.compareTo(before)>0) throw new IllegalStateException("Refinement worsened score");
    new SolutionVerifier(snapshot).verify(network);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("seed",seed); result.put("beforeScore",before); result.put("afterScore",network.score);
    result.put("constructionBefore",costBefore); result.put("constructionAfter",network.construction());
    result.put("lengthBefore",lengthBefore); result.put("lengthAfter",network.length);
    result.put("candidateChecks",checks-firstCheck); result.put("acceptedMoves",accepted-firstAccepted);
    result.put("coveragePreserved",true); result.put("elapsedMillis",(System.nanoTime()-started)/1000000);
    result.put("method","Economic merging; route shortcuts; tie-in and chamber relocation; bend relaxation; target regrouping");
    progress.accept(Map.of("phase","REFINED","seed",seed,"score",network.score,
        "constructionCost",network.construction(),"length",network.length));
    scores.clear(); groupCache.clear();
    return result;
  }

  private Set<String> coverage(Variant v) {
    Set<String> targets=new TreeSet<>();
    for(Component c:v.components) for(Branch b:c.branches)
      if(!targets.add(b.target.key)) throw new IllegalStateException("Duplicate refined target");
    return targets;
  }

  private BigDecimal score(Component c) {
    return scores.computeIfAbsent(c,optimizer::componentScore);
  }

  private Component resized(Root root, Coordinate hub, LineString trunk, List<Branch> branches) {
    try { return hydraulic.size(root,hub,trunk,branches); }
    catch(IllegalArgumentException ex) { return null; }
  }

  private Component choose(Component best,Component candidate,List<Component> rest) {
    if(candidate==null) return best;
    checks++;
    if(!optimizer.valid(candidate) || !optimizer.compatible(candidate,rest)) return best;
    return score(best).subtract(score(candidate)).compareTo(MIN_GAIN)>0 ? candidate : best;
  }

  private LineString line(Component c,int index) { return index<0?c.trunk:c.branches.get(index).line; }

  private Component replaceLine(Component c,int index,LineString changed) {
    if(index<0) return resized(c.root,c.hub,changed,c.branches);
    List<Branch> leaves=new ArrayList<>(c.branches); Branch old=leaves.get(index);
    leaves.set(index,new Branch(old.target,changed,old.dn));
    return resized(c.root,c.hub,c.trunk,leaves);
  }

  private Component shortcuts(Component c,List<Component> rest) {
    for(int index=c.trunk==null?0:-1;index<c.branches.size();index++) {
      boolean changed=true;
      while(changed) {
        changed=false; Coordinate[] points=line(c,index).getCoordinates();
        Component best=c;
        for(int width=points.length-1;width>=2;width--)
          for(int a=0;a+width<points.length;a++) {
            int b=a+width; List<Coordinate> out=new ArrayList<>();
            for(int k=0;k<=a;k++) out.add(points[k]);
            for(int k=b;k<points.length;k++) out.add(points[k]);
            best=choose(best,replaceLine(c,index,Metric.line(out.toArray(new Coordinate[0]))),rest);
          }
        if(best!=c) { c=best; changed=true; }
      }
    }
    return c;
  }

  private Component bends(Component c,List<Component> rest) {
    for(double step:new double[]{16,4,1,.25})
      for(int index=c.trunk==null?0:-1;index<c.branches.size();index++)
        for(int i=1;i<line(c,index).getNumPoints()-1;i++) {
          Coordinate[] original=line(c,index).getCoordinates(); Component best=c;
          List<Coordinate> positions=new ArrayList<>();
          positions.add(new LineSegment(original[i-1],original[i+1]).closestPoint(original[i]));
          for(int dx=-1;dx<=1;dx++) for(int dy=-1;dy<=1;dy++)
            if(dx!=0||dy!=0) positions.add(new Coordinate(original[i].x+dx*step,original[i].y+dy*step));
          for(Coordinate at:positions) {
            Coordinate[] points=original.clone(); points[i]=at;
            if(points[i-1].distance(at)<1e-5 || points[i+1].distance(at)<1e-5) continue;
            LineString changed=Metric.line(points);
            if(changed.getLength()>=line(c,index).getLength()-1e-7) continue;
            best=choose(best,replaceLine(c,index,changed),rest);
          }
          c=best;
        }
    return c;
  }

  private Component hubAt(Component c,Coordinate point) {
    Coordinate[] trunk=c.trunk.getCoordinates().clone(); trunk[trunk.length-1]=point;
    List<Branch> branches=new ArrayList<>();
    for(Branch b:c.branches) {
      Coordinate[] leaf=b.line.getCoordinates().clone(); leaf[0]=point;
      branches.add(new Branch(b.target,Metric.line(leaf),b.dn));
    }
    return resized(c.root,point,Metric.line(trunk),branches);
  }

  private Component moveHub(Component c,List<Component> rest) {
    if(c.trunk==null) return c;
    for(double step:new double[]{32,16,8,4,2,1,.25})
      for(int round=0;round<2;round++) {
        Component best=c;
        for(int dx=-1;dx<=1;dx++) for(int dy=-1;dy<=1;dy++)
          if(dx!=0||dy!=0) best=choose(best,hubAt(c,new Coordinate(c.hub.x+dx*step,c.hub.y+dy*step)),rest);
        if(best==c) break;
        c=best;
      }
    return c;
  }

  private Component rootAt(Component c,Root root) {
    LineString path=c.trunk==null?c.branches.get(0).line:c.trunk;
    Coordinate[] points=path.getCoordinates().clone(); points[0]=root.point;
    if(c.trunk!=null) return resized(root,c.hub,Metric.line(points),c.branches);
    return resized(root,null,null,List.of(new Branch(c.branches.get(0).target,Metric.line(points),50)));
  }

  private Component retie(Component c,List<Component> rest) {
    LineString path=c.trunk==null?c.branches.get(0).line:c.trunk;
    Coordinate anchor=path.getCoordinateN(1);
    Map<String,Root> roots=new TreeMap<>();
    // Project the first free route vertex, not only the remote consumer.
    for(Feature network:snapshot.nearestNetworks(anchor,8)) {
      LineString geometry=(LineString)network.geometry(); LengthIndexedLine index=new LengthIndexedLine(geometry);
      for(double base:new double[]{index.project(anchor),index.project(c.root.point)})
        for(double shift:new double[]{-64,-32,-16,-8,-4,-1,0,1,4,8,16,32,64}) {
          Coordinate at=index.extractPoint(Math.max(0,Math.min(geometry.getLength(),base+shift)));
          for(Root root:tie.candidatesAt(at)) roots.put(root.key(),root);
        }
    }
    Component best=c;
    for(Root root:roots.values()) best=choose(best,rootAt(c,root),rest);
    return best;
  }

  private LineString combined(Component c,Branch b) {
    if(c.trunk==null) return b.line;
    List<Coordinate> points=new ArrayList<>(Arrays.asList(c.trunk.getCoordinates()));
    Coordinate[] leaf=b.line.getCoordinates();
    for(int i=1;i<leaf.length;i++) points.add(leaf[i]);
    return Metric.line(points.toArray(new Coordinate[0]));
  }

  private List<Component> alternatives(List<Feature> targets,Component a,Component b) {
    List<String> ids=new ArrayList<>();targets.forEach(f->ids.add(f.key));Collections.sort(ids);
    String key=ids+"/"+a.root.key()+"/"+b.root.key()+"/"+a.hub+"/"+b.hub;
    List<Component> cached=groupCache.get(key);if(cached!=null)return cached;
    List<Component> result=new ArrayList<>();
    if(targets.size()==1) {
      Feature target=targets.get(0);
      for(Component source:List.of(a,b)) for(Branch branch:source.branches)
        if(branch.target.key.equals(target.key)) {
          Component direct=resized(source.root,null,null,List.of(new Branch(target,combined(source,branch),50)));
          if(direct!=null && optimizer.valid(direct)) result.add(direct);
        }
    } else {
      Map<String,Coordinate> hubs=new TreeMap<>();
      if(a.hub!=null)hubs.put(Metric.key(a.hub),a.hub);
      if(b.hub!=null)hubs.put(Metric.key(b.hub),b.hub);
      Coordinate center=new Coordinate();
      for(Feature f:targets){center.x+=f.geometry().getCoordinate().x;center.y+=f.geometry().getCoordinate().y;}
      center.x/=targets.size();center.y/=targets.size();
      for(Component source:List.of(a,b)) {
        LineString path=source.trunk==null?source.branches.get(0).line:source.trunk;
        LengthIndexedLine index=new LengthIndexedLine(path);
        for(double t:new double[]{index.project(center),path.getLength()*.4,path.getLength()*.7})
          if(t>1e-4 && t<path.getLength()-1e-4) {
            Coordinate p=index.extractPoint(t);hubs.put(Metric.key(p),p);
          }
      }
      for(Root root:List.of(a.root,b.root)) for(Coordinate hub:hubs.values()) {
        Component c=optimizer.shared(root,hub,targets);
        if(c!=null)result.add(c);
      }
    }
    result.sort(Comparator.comparing(this::score).thenComparing(c->c.root.key()));
    if(result.size()>5) result=new ArrayList<>(result.subList(0,5));
    groupCache.put(key,result);return result;
  }

  private void repartition(Variant network) {
    // Exchange targets between nearby components; also test 2+2 -> 1+3 regrouping.
    for(int round=0;round<2;round++) {
      Component oldA=null,oldB=null,newA=null,newB=null;BigDecimal gain=MIN_GAIN;
      for(int i=0;i<network.components.size();i++) for(int j=i+1;j<network.components.size();j++) {
        Component a=network.components.get(i),b=network.components.get(j);
        int count=a.branches.size()+b.branches.size(); if(count<3||count>4)continue;
        double separation=Double.POSITIVE_INFINITY;
        for(Branch x:a.branches)for(Branch y:b.branches)
          separation=Math.min(separation,x.target.geometry().distance(y.target.geometry()));
        if(separation>200)continue;
        List<Feature> targets=new ArrayList<>();a.branches.forEach(x->targets.add(x.target));b.branches.forEach(x->targets.add(x.target));
        targets.sort(Comparator.comparing(f->f.key));
        List<Component> rest=new ArrayList<>(network.components);rest.remove(a);rest.remove(b);
        BigDecimal old=score(a).add(score(b));
        for(int mask=1;mask<(1<<count)-1;mask++) {
          if((mask&1)==0)continue; // Each unordered partition once.
          List<Feature> left=new ArrayList<>(),right=new ArrayList<>();
          for(int k=0;k<count;k++) ((mask&(1<<k))!=0?left:right).add(targets.get(k));
          if(left.size()>3||right.size()>3)continue;
          for(Component ca:alternatives(left,a,b)) {
            if(!optimizer.compatible(ca,rest))continue;
            List<Component> others=new ArrayList<>(rest);others.add(ca);
            for(Component cb:alternatives(right,a,b)) {
              checks++;
              BigDecimal saving=old.subtract(score(ca)).subtract(score(cb));
              if(saving.compareTo(gain)<=0||!optimizer.compatible(cb,others))continue;
              gain=saving;oldA=a;oldB=b;newA=ca;newB=cb;
            }
          }
        }
      }
      if(newA==null)break;
      network.components.remove(oldA);network.components.remove(oldB);
      network.components.add(newA);network.components.add(newB);accepted++;
      optimizer.score(network);
    }
  }
}
