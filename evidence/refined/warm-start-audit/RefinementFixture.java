package com.heatroute.application;

import com.fasterxml.jackson.databind.node.TextNode;
import com.heatroute.domain.Model.*;
import com.heatroute.geo.Metric;
import com.heatroute.hydraulic.TreeHydraulics;
import com.heatroute.tiein.TieInPlanner;
import java.math.BigDecimal;
import java.util.*;
import org.locationtech.jts.geom.*;

/** Independent small case: shorten a legal detour, reuse the chamber, preserve source geometry. */
public class RefinementFixture {
  static Coordinate p(double x,double y){return new Coordinate(x,y);}
  static Feature f(String id,String type,String restriction,Geometry g,int dn,String flow){
    return new Feature(TextNode.valueOf(id),type,restriction,g,dn,new BigDecimal(flow));
  }
  public static void main(String[] args) {
    Feature network=f("main","heat_network","",Metric.line(p(0,-100),p(0,100)),300,"0");
    Feature chamber=f("existing","heat_chamber","",Metric.point(p(0,0)),300,"0");
    Feature target=f("target","oks_connection_point","",Metric.point(p(80,0)),0,"1");
    Feature house=f("house","restriction","oks",Metric.GF.createPolygon(new Coordinate[]{p(75,-5),p(85,-5),p(85,5),p(75,5),p(75,-5)}),0,"0");
    Snapshot snapshot=new Snapshot(List.of(network,chamber,target,house));
    Root root=new TieInPlanner(snapshot).at(p(0,50),null);
    LineString original=Metric.line(p(0,50),p(60,50),p(60,0),p(80,0));
    String oldGeometry=original.toText();
    Variant v=new Variant();v.components.add(new TreeHydraulics().size(root,null,null,List.of(new Branch(target,original,50))));
    Optimizer optimizer=new Optimizer(snapshot,x->{});optimizer.score(v);BigDecimal before=v.score;
    new SolutionVerifier(snapshot).verify(v);
    new NetworkRefiner(snapshot,optimizer,x->{}).refine(v,1);
    if(!original.toText().equals(oldGeometry))throw new AssertionError("Source geometry mutated");
    if(v.components.size()!=1||v.components.get(0).branches.size()!=1||!v.missing.isEmpty())throw new AssertionError("Coverage changed");
    if(v.score.compareTo(before)>=0)throw new AssertionError("Detour not improved");
    if(v.components.get(0).root.existingChamber!=chamber)throw new AssertionError("10 m chamber reuse rule not applied");
    if(Math.abs(v.length.doubleValue()-80)>1e-6)throw new AssertionError("Fixture's known shortest 80 m route not found");
    new SolutionVerifier(snapshot).verify(v);
    System.out.println("PASS: known 80 m optimum; existing chamber reused; source immutable; coverage, geometry, sizing and score verified");
  }
}
