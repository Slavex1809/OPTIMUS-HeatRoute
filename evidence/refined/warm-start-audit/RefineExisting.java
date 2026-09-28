package com.heatroute.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.heatroute.domain.Model;
import com.heatroute.domain.Model.*;
import com.heatroute.export.GeoJsonExporter;
import com.heatroute.geo.Metric;
import com.heatroute.hydraulic.TreeHydraulics;
import com.heatroute.ingest.GeoJsonInput;
import com.heatroute.tiein.TieInPlanner;
import java.nio.file.*;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.geojson.GeoJsonReader;

/** Scratch audit harness: reconstruct a prior exported star forest and refine it. */
public class RefineExisting {
  static Map<String,List<JsonNode>> outgoing=new TreeMap<>();
  static Map<String,JsonNode> nodes=new TreeMap<>();
  static Snapshot snapshot;
  static Geometry geometry(JsonNode node)throws Exception {
    return Metric.transform(new GeoJsonReader().read(node.get("geometry").toString()),true);
  }
  static class PathResult { String end;LineString line; }
  static PathResult walk(JsonNode first)throws Exception {
    List<Coordinate> points=new ArrayList<>();JsonNode edge=first;String end;
    while(true) {
      Coordinate[] cs=geometry(edge).getCoordinates();
      for(int i=points.isEmpty()?0:1;i<cs.length;i++)points.add(cs[i]);
      end=Model.key(edge.get("properties").get("end_node_id"));
      List<JsonNode> next=outgoing.getOrDefault(end,List.of());
      if(next.size()!=1)break;
      edge=next.get(0);
    }
    PathResult result=new PathResult();result.end=end;result.line=Metric.line(points.toArray(new Coordinate[0]));return result;
  }
  public static void main(String[] args)throws Exception {
    snapshot=new GeoJsonInput().read(Files.newInputStream(Path.of(args[0])));
    JsonNode export=GeoJsonInput.JSON.readTree(Path.of(args[1]).toFile());
    Set<String> incoming=new TreeSet<>();
    for(JsonNode f:export.get("features")) {
      JsonNode p=f.get("properties");if(!p.get("variant_id").asText().equals("v1"))continue;
      String id=Model.key(p.get("id"));nodes.put(id,f);
      if(p.get("object_type").asText().equals("heat_network")) {
        outgoing.computeIfAbsent(Model.key(p.get("start_node_id")),k->new ArrayList<>()).add(f);
        incoming.add(Model.key(p.get("end_node_id")));
      }
    }
    Variant v=new Variant();TieInPlanner planner=new TieInPlanner(snapshot);TreeHydraulics sizing=new TreeHydraulics();
    for(String start:outgoing.keySet())if(!incoming.contains(start)) {
      Feature existing=snapshot.byId.get(start);
      Coordinate coordinate=existing==null?geometry(nodes.get(start)).getCoordinate():existing.geometry().getCoordinate();
      Root root=planner.at(coordinate,existing);
      for(JsonNode edge:outgoing.get(start)) {
        PathResult first=walk(edge);List<Branch> leaves=new ArrayList<>();
        List<JsonNode> forks=outgoing.getOrDefault(first.end,List.of());
        if(forks.isEmpty())leaves.add(new Branch(snapshot.byId.get(first.end),first.line,50));
        else for(JsonNode branch:forks) {
          PathResult leaf=walk(branch);
          if(!outgoing.getOrDefault(leaf.end,List.of()).isEmpty())throw new IllegalStateException("Nested tree unsupported by audit harness");
          leaves.add(new Branch(snapshot.byId.get(leaf.end),leaf.line,50));
        }
        v.components.add(sizing.size(root,forks.isEmpty()?null:first.line.getEndPoint().getCoordinate(),forks.isEmpty()?null:first.line,leaves));
      }
    }
    Set<String> assigned=new TreeSet<>();for(Component c:v.components)for(Branch b:c.branches)assigned.add(b.target.key);
    for(Feature f:snapshot.targets)if(!assigned.contains(f.key))v.missing.add(f);
    Optimizer optimizer=new Optimizer(snapshot,p->System.out.println(p));optimizer.score(v);
    new SolutionVerifier(snapshot).verify(v);
    System.out.println("IMPORTED cost="+v.construction()+" length="+v.length+" score="+v.score);
    Map<LineString,String> originalLines=new IdentityHashMap<>();
    for(Component c:v.components)for(LineString line:Optimizer.lines(c))originalLines.put(line,line.toText());
    var report=new NetworkRefiner(snapshot,optimizer,p->System.out.println(p)).refine(v,1);
    for(var entry:originalLines.entrySet())if(!entry.getValue().equals(entry.getKey().toText()))
      throw new IllegalStateException("Refinement mutated a source route");
    System.out.println("PASS: original route geometries unchanged; refinement monotone and coverage preserved");
    Path dest=Path.of(args[2]);Files.createDirectories(dest);
    new GeoJsonExporter().write(snapshot,List.of(v),Files.newOutputStream(dest.resolve("result.geojson")));
    GeoJsonInput.JSON.writerWithDefaultPrettyPrinter().writeValue(dest.resolve("refinement.json").toFile(),report);
    System.out.println(report);snapshot.close();
  }
}
