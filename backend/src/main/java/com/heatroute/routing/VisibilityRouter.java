package com.heatroute.routing;

import com.heatroute.constraints.ConstraintEngine;
import com.heatroute.constraints.EntryPolicy;
import com.heatroute.cost.CostFunction;
import java.math.BigDecimal;
import com.heatroute.domain.Model.*;
import com.heatroute.geo.Metric;
import com.heatroute.hydraulic.DiameterCatalog;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;

public final class VisibilityRouter {
  private final ConstraintEngine rules;
  private final Snapshot snapshot;
  private final TerminalEntryPlanner terminalPlanner;
  private final Map<String, Optional<LineString>> cache =
      new LinkedHashMap<String, Optional<LineString>>(256, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Optional<LineString>> e) {
          return size() > 2000;
        }
      };
  public long cacheHits, edgeChecks;
  public final Map<String, Integer> rejected = new TreeMap<>();

  public VisibilityRouter(Snapshot s, ConstraintEngine c) {
    snapshot = s;
    rules = c;
    terminalPlanner = new TerminalEntryPlanner(s,c);
  }

  public List<Map<String,Object>> prepareTerminalEntries() {
    if (!EntryPolicy.nearestOnly()) {
      List<Map<String,Object>> plans = new ArrayList<>();
      for (Feature target : snapshot.targets) {
        int dn = DiameterCatalog.select(target.flow, 0, 0).dn;
        Feature owner = null;
        for (Feature f : snapshot.nearby(target.envelope))
          if (f.restriction.equals("oks") && f.geometry().getDimension() == 2
              && f.geometry().covers(target.geometry())) { owner = f; break; }
        Map<String,Object> plan = new LinkedHashMap<>();
        plan.put("targetId", target.id);
        plan.put("diameterLowerBound", dn);
        List<List<Double>> endpoints = new ArrayList<>();
        if (owner != null) {
          plan.put("ownBuildingId", owner.id);
          for (Coordinate gate : entries(owner, target, dn)) {
            Coordinate wgs = Metric.transform(Metric.point(gate), false).getCoordinate();
            endpoints.add(List.of(wgs.x, wgs.y));
          }
        }
        plan.put("status", owner == null ? "NO_OWN_POLYGON" : endpoints.isEmpty()
            ? "NO_SAMPLED_ADMISSIBLE_ENTRY" : "ADMISSIBLE_ENTRIES_FOUND");
        plan.put("entryPolicy", EntryPolicy.name());
        plan.put("validatedOuterEndpointsWgs84", endpoints);
        plan.put("validatedEndpointCount", endpoints.size());
        plan.put("candidateScope", "Geometry-derived facade samples at minimum flow diameter; "
            + "all terminal-segment checks except global nearest-boundary requirement; "
            + "full-route feasibility and diameter are rechecked after routing; finite search");
        plans.add(plan);
      }
      return plans;
    }
    for(Feature target:snapshot.targets)
      for(var row:DiameterCatalog.ALL)
        if(row.capacity.compareTo(target.flow)>=0)terminalPlanner.legalGates(target,row.dn);
    return terminalPlanner.diagnostics();
  }

  public LineString route(
      Coordinate start, Coordinate end, int dn, Set<String> incident, Feature target) {
    String key =
        Metric.key(start)
            + "/"
            + Metric.key(end)
            + "/"
            + dn
            + "/"
            + new TreeSet<>(incident)
            + "/"
            + (target == null ? "" : target.key);
    Optional<LineString> memo = cache.get(key);
    if (memo != null) {
      cacheHits++;
      return memo.map(g -> (LineString) g.copy()).orElse(null);
    }
    LineString result = chooseEntry(start, end, dn, incident, target, true);
    cache.put(key, Optional.ofNullable(result));
    return result;
  }

  public LineString fast(
      Coordinate start, Coordinate end, int dn, Set<String> incident, Feature target) {
    String key =
        "fast/"
            + Metric.key(start)
            + "/"
            + Metric.key(end)
            + "/"
            + dn
            + "/"
            + new TreeSet<>(incident)
            + "/"
            + (target == null ? "" : target.key);
    Optional<LineString> memo = cache.get(key);
    if (memo != null) {
      cacheHits++;
      return memo.map(g -> (LineString) g.copy()).orElse(null);
    }
    LineString result = chooseEntry(start, end, dn, incident, target, false);
    cache.put(key, Optional.ofNullable(result));
    return result;
  }

  private final Map<String, List<Coordinate>> entryCache = new HashMap<>();

  private BigDecimal price(LineString line, int dn, Set<String> incident, Feature target) {
    var check = rules.assess(line, dn, incident, target);
    BigDecimal total = BigDecimal.ZERO;
    for (var part : rules.partition(line, check.spans))
      total = total.add(CostFunction.pipe(part.b - part.a, dn, part.factor));
    return total;
  }

  private LineString cheaper(LineString a, LineString b, int dn,
      Set<String> incident, Feature target) {
    if (b == null) return a;
    if (a == null) return b;
    int cmp = price(a, dn, incident, target).compareTo(price(b, dn, incident, target));
    if (cmp != 0) return cmp < 0 ? a : b;
    cmp = Double.compare(a.getLength(), b.getLength());
    if (cmp != 0) return cmp < 0 ? a : b;
    return a.toText().compareTo(b.toText()) <= 0 ? a : b;
  }

  /** Facade samples are geometry-derived, with no dataset-specific coordinates or IDs. */
  private List<Coordinate> entries(Feature owner, Feature target, int dn) {
    String key = target.key + "/" + owner.key + "/" + dn;
    if (entryCache.containsKey(key)) return entryCache.get(key);
    Coordinate end = target.geometry().getCoordinate();
    TreeMap<String, Coordinate> boundaryPoints = new TreeMap<>();
    Geometry boundary = owner.geometry().getBoundary();
    for (int k = 0; k < boundary.getNumGeometries(); k++) {
      Coordinate[] cs = boundary.getGeometryN(k).getCoordinates();
      for (int i = 1; i < cs.length; i++) {
        LineSegment seg = new LineSegment(cs[i - 1], cs[i]);
        Coordinate nearest = seg.closestPoint(end);
        boundaryPoints.put(Metric.key(nearest), nearest);
        int samples = Math.min(32, Math.max(2, (int) Math.ceil(seg.getLength() / 8)));
        for (int j = 0; j <= samples; j++) {
          Coordinate c = seg.pointAlong((double) j / samples);
          boundaryPoints.put(Metric.key(c), c);
        }
      }
    }
    double clearance = ConstraintEngine.gap("oks", dn) + DiameterCatalog.get(dn).width / 2;
    TreeMap<String, Coordinate> portals = new TreeMap<>();
    for (Coordinate b : boundaryPoints.values()) {
      double length = b.distance(end);
      if (length < 1e-7) continue;
      double dx = (b.x - end.x) / length, dy = (b.y - end.y) / length;
      // Extend the straight entry beyond the building's clearance envelope.
      for (double extension = clearance + .15; extension <= clearance * 5; extension += clearance / 2) {
        Coordinate a = new Coordinate(b.x + dx * extension, b.y + dy * extension);
        if (owner.geometry().distance(Metric.point(a)) < clearance + .01) continue;
        if (valid(Metric.line(a, end), dn, Set.of(), target)) portals.put(Metric.key(a), a);
        break;
      }
    }
    List<Coordinate> result = new ArrayList<>(portals.values());
    entryCache.put(key, result);
    return result;
  }

  private LineString chooseEntry(Coordinate start, Coordinate end, int dn,
      Set<String> incident, Feature target, boolean deep) {
    if (EntryPolicy.nearestOnly() || target == null)
      return compute(start, end, dn, incident, target, deep);
    Feature owner = null;
    for (Feature f : snapshot.nearby(target.envelope))
      if (f.restriction.equals("oks") && f.geometry().getDimension() == 2
          && f.geometry().covers(target.geometry())) { owner = f; break; }
    if (owner == null) return compute(start, end, dn, incident, target, deep);
    double limit = DiameterCatalog.get(dn).maxLength;
    LineString best = compute(start, end, dn, incident, target, false);
    LineString direct = Metric.line(start, end);
    if (direct.getLength() <= limit && valid(direct, dn, incident, target)) best = direct;
    List<Coordinate> candidates = new ArrayList<>(entries(owner, target, dn));
    candidates.sort(Comparator.comparingDouble((Coordinate c) -> start.distance(c) + c.distance(end))
        .thenComparing(Metric::key));
    int fastChecks = 0;
    BigDecimal bestPrice = best == null ? null : price(best, dn, incident, target);
    for (Coordinate a : candidates) {
      if (start.distance(a) + a.distance(end) > limit || !Metric.turn(start, a, end)) continue;
      if (bestPrice != null && CostFunction.pipe(start.distance(a)+a.distance(end),dn,1)
          .compareTo(bestPrice) >= 0) continue;
      if (fastChecks++ >= 64) break;
      LineString line = Metric.line(start, a, end);
      if (valid(line, dn, incident, target)) {
        best = cheaper(best, line, dn, incident, target);
        bestPrice = price(best, dn, incident, target);
      }
    }
    if (!deep) return best;
    // One search with several admissible terminal portals, instead of an A* per facade.
    List<Coordinate> goals = new ArrayList<>();
    for (Coordinate a : candidates) {
      double lower = start.distance(a) + a.distance(end);
      if (lower > limit) continue;
      if (best != null && CostFunction.pipe(lower, dn, 1)
          .compareTo(price(best, dn, incident, target)) >= 0) continue;
      goals.add(a);
      if (goals.size() >= 64) break;
    }
    if (!goals.isEmpty())
      for (double radius : new double[] {Math.max(40, start.distance(end)*.45),
          Math.max(150, start.distance(end)*1.2)}) {
        LineString line = searchPortals(start, goals, end, dn, incident, target, radius);
        best = cheaper(best, line, dn, incident, target);
        if (line != null) break;
      }
    return best;
  }

  private LineString compute(
      Coordinate start,
      Coordinate end,
      int dn,
      Set<String> incident,
      Feature target,
      boolean search) {
    if (start.distance(end) < 1e-5) return null;
    double limit = DiameterCatalog.get(dn).maxLength;
    if (start.distance(end) > limit) return null;
    LineString direct = Metric.line(start, end);
    if (valid(direct, dn, incident, target)) return direct;
    if(EntryPolicy.nearestOnly() && target!=null) {
      var terminalPlan=terminalPlanner.plan(target,dn);
      if(terminalPlan.owner!=null && terminalPlan.insideLength>1e-8) {
        List<Coordinate> goals=terminalPlanner.gates(target,dn,start);
        LineString best=null;
        for(Coordinate gate:goals) {
          LineString candidate=Metric.line(start,gate,end);
          if(Metric.turn(start,gate,end) && valid(candidate,dn,incident,target))
            best=cheaper(best,candidate,dn,incident,target);
        }
        if(best!=null || !search || goals.isEmpty())return best;
        for(double radius:new double[]{Math.max(40,start.distance(end)*.45),Math.max(150,start.distance(end)*1.2)}) {
          LineString path=searchPortals(start,goals,end,dn,incident,target,radius);
          if(path!=null)return path;
        }
        return null;
      }
    }
    Coordinate destination = end;
    Feature owner = null;
    if (target != null) {
      for (Feature f : snapshot.nearby(target.envelope))
        if (f.restriction.equals("oks")
            && f.geometry().getDimension() == 2
            && f.geometry().covers(target.geometry())) {
          owner = f;
          break;
        }
      if (owner != null) {
        Coordinate boundary =
            DistanceOp.nearestPoints(owner.geometry().getBoundary(), target.geometry())[0];
        double dx = boundary.x - end.x, dy = boundary.y - end.y, len = Math.hypot(dx, dy);
        if (len < 1e-8) {
          Coordinate center = owner.geometry().getInteriorPoint().getCoordinate();
          dx = end.x - center.x;
          dy = end.y - center.y;
          len = Math.hypot(dx, dy);
        }
        if (len < 1e-8) return null;
        double clearance = ConstraintEngine.gap("oks", dn) + DiameterCatalog.get(dn).width / 2;
        double extension = clearance + .12;
        destination =
            new Coordinate(boundary.x + dx / len * extension, boundary.y + dy / len * extension);
        for (int step = 0;
            step < 30 && owner.geometry().distance(Metric.point(destination)) < clearance + .02;
            step++) {
          extension += Math.max(.25, clearance / 4);
          destination =
              new Coordinate(boundary.x + dx / len * extension, boundary.y + dy / len * extension);
        }
        if (owner.geometry().distance(Metric.point(destination)) < clearance
            && !(EntryPolicy.nearestOnly() && search)) return null;
        if (!valid(Metric.line(destination, end), dn, Set.of(), target)
            && !(EntryPolicy.nearestOnly() && search)) return null;
      }
    }
    // Search several last-bend positions on the SAME mandatory nearest-entry ray.
    // Candidate spacing is a search budget, never a minimum permitted segment length.
    if (owner != null && EntryPolicy.nearestOnly() && search) {
      Coordinate boundary = DistanceOp.nearestPoints(owner.geometry().getBoundary(), target.geometry())[0];
      double length = boundary.distance(end);
      if (length > 1e-8) {
        double ux = (boundary.x-end.x)/length, uy = (boundary.y-end.y)/length;
        double clearance = ConstraintEngine.gap("oks", dn) + DiameterCatalog.get(dn).width/2;
        TreeSet<Double> offsets = new TreeSet<>();
        offsets.add(destination.distance(boundary));
        offsets.add(clearance + .0001);
        double projection = (start.x-boundary.x)*ux + (start.y-boundary.y)*uy;
        if (projection > 0) offsets.add(projection);
        for (double extra : new double[]{.02,.12,.5,1,2,4,8,16,32,64,128})
          offsets.add(clearance + extra);
        List<Coordinate> goals = new ArrayList<>();
        for (double offset : offsets) {
          Coordinate goal = new Coordinate(boundary.x+ux*offset,boundary.y+uy*offset);
          if (goal.distance(end)+goal.distance(start)>limit) continue;
          if (owner.geometry().distance(Metric.point(goal)) < clearance-1e-6) continue;
          if (!valid(Metric.line(goal,end),dn,Set.of(),target)) continue;
          goals.add(goal);
        }
        LineString best = null;
        for (Coordinate goal : goals) {
          LineString candidate = Metric.line(start,goal,end);
          if (!Metric.turn(start,goal,end) || !valid(candidate,dn,incident,target)) continue;
          best = cheaper(best,candidate,dn,incident,target);
        }
        if (best != null) return best;
        if (goals.isEmpty()) return null;
        for (double radius : new double[]{Math.max(40,start.distance(destination)*.45),Math.max(150,start.distance(destination)*1.2)}) {
          LineString path=searchPortals(start,goals,end,dn,incident,target,radius);
          if(path!=null) return path;
        }
        return null;
      }
    }
    if (destination.distance(end) > 1e-6 && Metric.turn(start, destination, end)) {
      LineString approach = Metric.line(start, destination, end);
      if (approach.getLength() <= limit && valid(approach, dn, incident, target)) return approach;
    }
    if (!search) return null;
    for (double radius :
        new double[] {
          Math.max(40, start.distance(destination) * .45),
          Math.max(150, start.distance(destination) * 1.2)
        }) {
      LineString path = search(start, destination, end, dn, incident, target, radius);
      if (path != null) return path;
    }
    return null;
  }

  private LineString search(
      Coordinate start,
      Coordinate destination,
      Coordinate terminal,
      int dn,
      Set<String> incident,
      Feature target,
      double radius) {
    return searchPortals(start, List.of(destination), terminal, dn, incident, target, radius);
  }

  private LineString searchPortals(Coordinate start, List<Coordinate> goals, Coordinate terminal,
      int dn, Set<String> incident, Feature target, double radius) {
    double max = DiameterCatalog.get(dn).maxLength;
    Envelope region = new Envelope(start, terminal);
    for (Coordinate goal : goals) region.expandToInclude(goal);
    region.expandBy(radius);
    TreeMap<String, Coordinate> unique = new TreeMap<>();
    List<Geometry> forbiddenBuffers = new ArrayList<>();
    List<Geometry> boundaries = new ArrayList<>();
    for (Feature f : snapshot.nearby(region)) {
      String type = f.type.equals("heat_network") ? "heat_network" : f.restriction;
      if (!ConstraintEngine.forbidden(type) && ConstraintEngine.factor(type) == 1) continue;
      double offset =
          ConstraintEngine.gap(type, dn)
              + DiameterCatalog.get(dn).width / 2
              + ConstraintEngine.halfOther(f)
              + .60;
      if (!ConstraintEngine.forbidden(type)) offset += 3;
      BufferParameters bufferParameters =
          new BufferParameters(4, BufferParameters.CAP_SQUARE, BufferParameters.JOIN_MITRE, 5);
      Geometry boundary =
          DouglasPeuckerSimplifier.simplify(
              BufferOp.bufferOp(f.geometry(), offset, bufferParameters), .5);
      if (ConstraintEngine.forbidden(type)) forbiddenBuffers.add(boundary);
      else boundaries.add(boundary);
    }
    if (!forbiddenBuffers.isEmpty()) boundaries.add(UnaryUnionOp.union(forbiddenBuffers));
    for (Geometry boundary : boundaries)
      for (Coordinate c : boundary.getCoordinates())
        if (c.distance(start) + c.distance(terminal) <= max
            && region.contains(c)) unique.putIfAbsent(Metric.key(c), c);
    List<Coordinate> candidates = new ArrayList<>(unique.values());
    candidates.sort(
        Comparator.comparingDouble((Coordinate c) -> c.distance(start) + c.distance(terminal))
            .thenComparing(Metric::key));
    if (candidates.size() > 240) candidates = new ArrayList<>(candidates.subList(0, 240));
    List<Coordinate> nodes = new ArrayList<>();
    nodes.add(start);
    nodes.addAll(goals);
    nodes.addAll(candidates);
    int n = nodes.size();
    double[] finalWeights = new double[goals.size()];
    for (int i = 0; i < goals.size(); i++) {
      Coordinate goal = goals.get(i);
      if (goal.distance(terminal) <= 1e-6) continue;
      var leg = Metric.line(goal, terminal);
      var check = rules.assess(leg, dn, Set.of(), target);
      if (!check.valid()) finalWeights[i] = Double.POSITIVE_INFINITY;
      else for (var part : rules.partition(leg, check.spans))
        finalWeights[i] += (part.b - part.a) * part.factor;
    }
    java.util.function.ToDoubleFunction<Coordinate> heuristic = point -> {
      double lower = Double.POSITIVE_INFINITY;
      for (int i = 0; i < goals.size(); i++)
        lower = Math.min(lower, point.distance(goals.get(i)) + finalWeights[i]);
      return lower;
    };
    Map<Long, Double> edges = new HashMap<>();
    class State {
      final long id, serial;
      final double g, h, metres;
      final State parent;
      boolean active = true;
      State(long id, long serial, double g, double h, double metres, State parent) {
        this.id = id; this.serial = serial; this.g = g; this.h = h;
        this.metres = metres; this.parent = parent;
      }
    }
    // Keep both price and physical length: a cheaper but longer prefix cannot
    // dominate a shorter prefix near the hydraulic length limit.
    Map<Long, List<State>> labels = new HashMap<>();
    PriorityQueue<State> queue = new PriorityQueue<>(
        Comparator.comparingDouble((State x) -> x.g + x.h)
            .thenComparingDouble(x -> x.g).thenComparingLong(x -> x.id)
            .thenComparingLong(x -> x.serial));
    State initial = new State(0, 0, 0, heuristic.applyAsDouble(start), 0, null);
    queue.add(initial);
    labels.put(0L, new ArrayList<>(List.of(initial)));
    long serial = 1;
    int expanded = 0;
    while (!queue.isEmpty() && expanded++ < 4000) {
      State state = queue.poll();
      if (!state.active) continue;
      int prev = (int) (state.id / n) - 1, u = (int) (state.id % n);
      Coordinate point = nodes.get(u);
      if (u >= 1 && u <= goals.size()) {
        Coordinate destination = point;
        if (destination.distance(terminal) > 1e-6 && prev >= 0
            && !Metric.turn(nodes.get(prev), point, terminal)) continue;
        List<Coordinate> coords = new ArrayList<>();
        for (State at = state; at != null; at = at.parent)
          coords.add(nodes.get((int) (at.id % n)));
        Collections.reverse(coords);
        if (destination.distance(terminal) > 1e-6) coords.add(terminal);
        LineString route = Metric.line(coords.toArray(new Coordinate[0]));
        if (route.getLength() <= max + 1e-6 && valid(route, dn, incident, target)) return route;
        continue;
      }
      for (int v = 1; v < n; v++) {
        if (v == u || v == prev) continue;
        Coordinate next = nodes.get(v);
        double len = point.distance(next);
        if (len < .01 || state.metres + len + next.distance(terminal) > max) continue;
        if (prev >= 0 && !Metric.turn(nodes.get(prev), point, next)) continue;
        long edge = (long) u * n + v;
        Double weight = edges.get(edge);
        if (weight == null) {
          LineString segment = Metric.line(point, next);
          var check = rules.assess(segment, dn, u == 0 ? incident : Set.of(), null);
          edgeChecks++;
          if (!check.valid()) {
            rejected.merge(check.error.split(":", 2)[0], 1, Integer::sum);
            weight = Double.POSITIVE_INFINITY;
          } else {
            // Cost in equivalent metres; base tariff is fixed for this DN.
            weight = 0.0;
            for (var part : rules.partition(segment, check.spans))
              weight += (part.b - part.a) * part.factor;
          }
          edges.put(edge, weight);
        }
        if (!Double.isFinite(weight)) continue;
        long id = (long) (u + 1) * n + v;
        double g = state.g + weight, metres = state.metres + len;
        List<State> front = labels.computeIfAbsent(id, k -> new ArrayList<>());
        boolean dominated = false;
        for (State old : front)
          if (old.g <= g + 1e-8 && old.metres <= metres + 1e-8) { dominated = true; break; }
        if (dominated) continue;
        for (Iterator<State> it = front.iterator(); it.hasNext();) {
          State old = it.next();
          if (g <= old.g + 1e-8 && metres <= old.metres + 1e-8) {
            old.active = false; it.remove();
          }
        }
        State nextState = new State(id, serial++, g, v <= goals.size() ? finalWeights[v-1] : heuristic.applyAsDouble(next), metres, state);
        front.add(nextState);
        queue.add(nextState);
      }
    }
    return null;
  }

  private boolean valid(LineString g, int dn, Set<String> incident, Feature target) {
    edgeChecks++;
    var c = rules.assess(g, dn, incident, target);
    if (!c.valid()) rejected.merge(c.error.split(":", 2)[0], 1, Integer::sum);
    return c.valid();
  }
}
