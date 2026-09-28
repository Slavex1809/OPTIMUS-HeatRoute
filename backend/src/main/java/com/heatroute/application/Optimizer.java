package com.heatroute.application;

import com.heatroute.constraints.ConstraintEngine;
import com.heatroute.constraints.EntryPolicy;
import com.heatroute.constraints.ConstraintEngine.Span;
import com.heatroute.cost.CostFunction;
import com.heatroute.domain.Model.*;
import com.heatroute.geo.Metric;
import com.heatroute.hydraulic.*;
import com.heatroute.routing.VisibilityRouter;
import com.heatroute.tiein.TieInPlanner;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import org.locationtech.jts.geom.*;

public final class Optimizer {
  private final Snapshot s;
  private final ConstraintEngine rules;
  private final VisibilityRouter router;
  private final TieInPlanner tie;
  private final TreeHydraulics hydraulic = new TreeHydraulics();
  private final Map<String, List<Component>> independent = new TreeMap<>();
  private final Consumer<Map<String, Object>> progress;
  private int evaluated, merges;

  public Optimizer(Snapshot s, Consumer<Map<String, Object>> progress) {
    this.s = s;
    rules = new ConstraintEngine(s);
    router = new VisibilityRouter(s, rules);
    tie = new TieInPlanner(s);
    this.progress = progress;
  }

  public static final class Result {
    public final List<Variant> variants;
    public final Map<String, Object> report;

    Result(List<Variant> v, Map<String, Object> r) {
      variants = v;
      report = r;
    }
  }

  public Result solve() {
    long start = System.nanoTime();
    var terminalPlans = router.prepareTerminalEntries();
    progress.accept(Map.of("phase","TERMINAL_ENTRIES_READY","targets",terminalPlans.size()));
    int done = 0;
    for (Feature target : s.targets) {
      independent.put(target.key, individual(target));
      progress.accept(
          Map.of(
              "phase",
              "ROUTING",
              "targetsDone",
              ++done,
              "targetsTotal",
              s.targets.size(),
              "edgeChecks",
              router.edgeChecks,
              "targetId",
              target.id,
              "candidates",
              independent.get(target.key).size()));
    }
    List<Variant> portfolio = new ArrayList<>();
    List<Variant> baselines = new ArrayList<>();
    for (int strategy = 0; strategy < 4; strategy++) {
      Variant v = assemble(strategy);
      repair(v);
      portfolio.add(v);
      baselines.add(v);
      if (strategy < 2) {
        Variant shared = copy(v);
        merge(shared);
        score(shared);
        portfolio.add(shared);
      }
      progress.accept(
          Map.of(
              "phase",
              "OPTIMIZING",
              "variantsEvaluated",
              portfolio.size(),
              "edgeChecks",
              router.edgeChecks));
    }
    for (Variant extra : beamAssemble()) {
      repair(extra);
      portfolio.add(extra);
    }
    // Never trade a reachable goal for a lower monetary score. Keep maximal coverage first.
    int missing = portfolio.stream().mapToInt(v -> v.missing.size()).min().orElse(s.targets.size());
    portfolio.removeIf(v -> v.missing.size() != missing);
    Comparator<Variant> order = Comparator.comparing((Variant v) -> v.score)
        .thenComparing(v -> v.total()).thenComparing(v -> v.length).thenComparing(v -> v.fingerprint);
    portfolio.sort(order);
    List<Variant> seeds = new ArrayList<>(portfolio.subList(0, Math.min(3, portfolio.size())));
    NetworkRefiner refiner = new NetworkRefiner(s, this, progress);
    List<Map<String,Object>> refinementReports = new ArrayList<>();
    int seedNumber = 0;
    for (Variant seed : seeds) {
      Variant polished = copy(seed);
      score(polished);
      refinementReports.add(refiner.refine(polished, ++seedNumber));
      portfolio.add(polished);
    }
    portfolio.sort(
        Comparator.comparing((Variant v) -> v.score)
            .thenComparing(v -> v.total())
            .thenComparing(v -> v.length)
            .thenComparing(v -> v.fingerprint));
    List<Variant> selected = new ArrayList<>();
    for (Variant v : portfolio) {
      boolean duplicate = false;
      for (Variant a : selected) if (similar(v, a)) duplicate = true;
      if (!duplicate) selected.add(v);
      if (selected.size() == 3) break;
    }
    for (Variant v : selected) new SolutionVerifier(s).verify(v);
    List<Map<String, Object>> explanations = new ArrayList<>();
    int rank = 0;
    for (Variant v : selected) {
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("variantId", "v" + (++rank));
      e.put("fingerprint", v.fingerprint);
      e.put("pipeCost", v.pipeCost);
      e.put("chamberCost", v.chamberCost);
      e.put("tieInCost", v.tieCost);
      e.put("penalty", v.penalty);
      e.put("constructionCost", v.construction());
      e.put(
          "scoreCostContribution",
          v.total().multiply(new BigDecimal("0.7")).divide(new BigDecimal("25000000")));
      e.put(
          "scoreLengthContribution",
          v.length.multiply(new BigDecimal("0.3")).divide(new BigDecimal("100")));
      e.put("sharedComponents", v.components.stream().filter(c -> c.trunk != null).count());
      Variant baseline =
          baselines.stream()
              .filter(b -> sameCoverage(b, v))
              .min(Comparator.comparing(b -> b.score))
              .orElse(null);
      if (baseline != null) {
        e.put("comparisonBaselineScore", baseline.score);
        e.put("scoreImprovement", baseline.score.subtract(v.score));
        e.put("constructionSaving", baseline.construction().subtract(v.construction()));
        e.put(
            "comparisonMeaning",
            "Best found pre-merge network with the same connected targets; not global optimum");
      }
      e.put("targetDiagnostics", TargetDiagnostics.describe(s, v));
      explanations.add(e);
    }
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("algorithm", EntryPolicy.nearestOnly()
        ? "OPTIMUS reverse terminal intervals / 2.1"
        : "OPTIMUS admissible entries, joint routing and monotone network refinement / 2.2");
    report.put("terminalEntryPlans",terminalPlans);
    report.put("networkRefinement", refinementReports);
    report.put("entryPolicy", EntryPolicy.name());
    report.put("entryPolicyBasis", EntryPolicy.nearestOnly() ? "Organizer clarification 3: nearest boundary" : "User-selected interpretation: feasible facade entry selected by construction cost");
    report.put(
        "specification", EntryPolicy.nearestOnly()
            ? "LCT updated specification and clarifications"
            : "LCT updated calculation rules with user-selected admissible facade entry; "
                + "global nearest-boundary requirement is not enforced");
    report.put("nearestBoundaryRequired", EntryPolicy.nearestOnly());
    report.put("targetDiagnostics", TargetDiagnostics.describe(s, selected.get(0)));
    report.put("variantExplanations", explanations);
    report.put("features", s.features.size());
    report.put("targets", s.targets.size());
    report.put("connected", s.targets.size() - missing);
    report.put("candidateEvaluations", evaluated);
    report.put("acceptedMerges", merges);
    report.put("cacheHits", router.cacheHits);
    report.put("edgeChecks", router.edgeChecks);
    report.put("rejectedChecks", router.rejected);
    report.put("elapsedMillis", (System.nanoTime() - start) / 1000000);
    report.put("globalOptimalityProven", false);
    report.put(
        "unconnectedMeans",
        "No feasible route found within documented search budget; not a proof of infeasibility");
    report.put("depthMode", false);
    report.put("coordinateSrid", 32637);
    report.put("warnings", s.warnings);
    report.put(
        "fingerprints",
        selected.stream().map(v -> v.fingerprint).collect(java.util.stream.Collectors.toList()));
    return new Result(selected, report);
  }

  private boolean sameCoverage(Variant a, Variant b) {
    Set<String> aa = new TreeSet<>(), bb = new TreeSet<>();
    a.missing.forEach(f -> aa.add(f.key));
    b.missing.forEach(f -> bb.add(f.key));
    return aa.equals(bb);
  }

  private List<Component> individual(Feature f) {
    List<Component> all = new ArrayList<>();
    List<Root> roots = tie.candidates(f.geometry().getCoordinate());
    int attempts = 0;
    for (Root root : roots) {
      LineString route =
          fastSized(root.point, f.geometry().getCoordinate(), f.flow, root.incident, f);
      if (route != null) {
        Component c = hydraulic.size(root, null, null, List.of(new Branch(f, route, 50)));
        if (valid(c)) {
          all.add(c);
          evaluated++;
        }
      }
    }
    if (all.size() < 6)
      for (Root root : roots) {
        if (attempts++ >= 16 && !all.isEmpty()) break;
        if (attempts > 30) break;
        LineString route =
            sizedRoute(root.point, f.geometry().getCoordinate(), f.flow, root.incident, f);
        if (route != null) {
          Component c = hydraulic.size(root, null, null, List.of(new Branch(f, route, 50)));
          if (valid(c)) {
            all.add(c);
            evaluated++;
          }
        }
        if (all.size() >= 6) break;
      }
    all.sort(Comparator.comparing(this::componentCost).thenComparing(c -> c.root.key()));
    return all;
  }

  private LineString fastSized(
      Coordinate from, Coordinate to, BigDecimal flow, Set<String> incident, Feature target) {
    int trials = 0;
    for (var row : DiameterCatalog.ALL) {
      if (row.capacity.compareTo(flow) < 0 || row.maxLength < from.distance(to)) continue;
      if (trials++ >= 4) break;
      LineString r = router.fast(from, to, row.dn, incident, target);
      if (r != null) return r;
    }
    return null;
  }

  private LineString sizedRoute(
      Coordinate from, Coordinate to, BigDecimal flow, Set<String> incident, Feature target) {
    int trials = 0;
    for (var row : DiameterCatalog.ALL) {
      if (row.capacity.compareTo(flow) < 0 || row.maxLength < from.distance(to)) continue;
      if (trials++ >= 4) break;
      LineString route = router.route(from, to, row.dn, incident, target);
      if (route != null) return route;
    }
    return null;
  }

  private Variant assemble(int strategy) {
    Variant v = new Variant();
    List<Feature> order = new ArrayList<>(s.targets);
    if (strategy == 1)
      order.sort(Comparator.comparing(Feature -> Feature.flow, Comparator.reverseOrder()));
    if (strategy == 2) Collections.reverse(order);
    if (strategy == 3)
      order.sort(
          Comparator.comparingInt((Feature f) -> independent.get(f.key).size())
              .thenComparing(f -> f.key));
    for (Feature f : order) {
      List<Component> candidates = new ArrayList<>(independent.get(f.key));
      if (strategy == 2 && candidates.size() > 1) Collections.rotate(candidates, -1);
      Component chosen = null;
      for (Component c : candidates)
        if (compatible(c, v.components)) {
          chosen = c;
          break;
        }
      if (chosen != null) v.components.add(chosen);
      else v.missing.add(f);
    }
    // Repair isolated ordering conflicts using alternatives for a conflicting component.
    for (Feature f : new ArrayList<>(v.missing))
      for (Component candidate : independent.get(f.key)) {
        List<Component> conflicts = new ArrayList<>();
        for (Component c : v.components) if (!compatible(candidate, List.of(c))) conflicts.add(c);
        if (conflicts.size() != 1) continue;
        Component old = conflicts.get(0);
        if (old.branches.size() != 1) continue;
        List<Component> remaining = new ArrayList<>(v.components);
        remaining.remove(old);
        if (!compatible(candidate, remaining)) continue;
        remaining.add(candidate);
        Component replacement = null;
        for (Component c : independent.get(old.branches.get(0).target.key))
          if (compatible(c, remaining)) {
            replacement = c;
            break;
          }
        if (replacement != null) {
          remaining.add(replacement);
          v.components.clear();
          v.components.addAll(remaining);
          v.missing.remove(f);
          break;
        }
      }
    score(v);
    return v;
  }

  // Keep several mutually compatible partial networks instead of one greedy choice.
  private List<Variant> beamAssemble() {
    List<Feature> order = new ArrayList<>(s.targets);
    order.sort(Comparator.comparingInt((Feature f)->independent.get(f.key).size()).thenComparing(f->f.key));
    List<Variant> beam = new ArrayList<>(); beam.add(new Variant());
    for (Feature f : order) {
      List<Variant> next = new ArrayList<>();
      for (Variant partial : beam) {
        int retained=0;
        for (Component candidate : independent.get(f.key)) {
          if (!compatible(candidate,partial.components)) continue;
          Variant v=copy(partial); v.components.add(candidate); score(v); next.add(v);
          if (++retained>=12) break;
        }
        Variant skipped=copy(partial); skipped.missing.add(f); score(skipped); next.add(skipped);
      }
      next.sort(Comparator.comparingInt((Variant v)->v.missing.size()).thenComparing(v->v.score).thenComparing(v->v.fingerprint));
      Map<String,Variant> unique=new LinkedHashMap<>();
      for(Variant v:next) { unique.putIfAbsent(v.fingerprint+v.missing.toString(),v); if(unique.size()>=48)break; }
      beam=new ArrayList<>(unique.values());
    }
    return beam.subList(0,Math.min(2,beam.size()));
  }

  private void repair(Variant v) {
    for (Feature f : new ArrayList<>(v.missing)) {
      if (independent.get(f.key).isEmpty()) continue;
      Component joined = null, replaced = null;
      List<Component> nearby = new ArrayList<>(v.components);
      nearby.sort(
          Comparator.comparingDouble(
              c -> c.branches.get(0).target.geometry().distance(f.geometry())));
      for (Component base : nearby) {
        if (base.branches.size() != 1
            || base.branches.get(0).target.geometry().distance(f.geometry()) > 200) continue;
        for (Component candidate : sharedOnPath(base, f, true)) {
          List<Component> rest = new ArrayList<>(v.components);
          rest.remove(base);
          if (compatible(candidate, rest)
              && (joined == null
                  || componentScore(candidate).compareTo(componentScore(joined)) < 0)) {
            joined = candidate;
            replaced = base;
          }
        }
        if (joined != null) break;
      }
      if (joined != null) {
        v.components.remove(replaced);
        v.components.add(joined);
        v.missing.remove(f);
        merges++;
        continue;
      }
      List<Feature> obstacles = new ArrayList<>(s.features);
      int id = 0;
      for (Component c : v.components)
        for (LineString l : lines(c))
          obstacles.add(
              new Feature(
                  com.fasterxml.jackson.databind.node.TextNode.valueOf("__reserved_" + (++id)),
                  "restriction",
                  "reserved_route",
                  l,
                  0,
                  BigDecimal.ZERO));
      Snapshot local = new Snapshot(obstacles);
      VisibilityRouter alternate = new VisibilityRouter(local, new ConstraintEngine(local));
      int tried = 0;
      for (Root root : tie.candidates(f.geometry().getCoordinate())) {
        if (tried++ >= 32) break;
        Component candidate = null;
        int dns = 0;
        for (var row : DiameterCatalog.ALL) {
          if (row.capacity.compareTo(f.flow) < 0
              || row.maxLength < root.point.distance(f.geometry().getCoordinate())) continue;
          if (dns++ >= 3) break;
          LineString path =
              alternate.route(root.point, f.geometry().getCoordinate(), row.dn, root.incident, f);
          if (path == null) continue;
          Component c = hydraulic.size(root, null, null, List.of(new Branch(f, path, 50)));
          if (valid(c) && compatible(c, v.components)) {
            candidate = c;
            break;
          }
        }
        if (candidate != null) {
          v.components.add(candidate);
          v.missing.remove(f);
          evaluated++;
          break;
        }
      }
      progress.accept(
          Map.of(
              "phase",
              "REPAIRING",
              "targetId",
              f.id,
              "connected",
              s.targets.size() - v.missing.size(),
              "targetsTotal",
              s.targets.size()));
    }
    score(v);
  }

  void merge(Variant variant) {
    boolean improved = true;
    int rounds = 0;
    while (improved && rounds++ < s.targets.size()) {
      improved = false;
      Component best = null;
      Component ba = null, bb = null;
      BigDecimal saving = BigDecimal.ZERO;
      for (int i = 0; i < variant.components.size(); i++)
        for (int j = i + 1; j < variant.components.size(); j++) {
          Component a = variant.components.get(i), b = variant.components.get(j);
          if (a.branches.size() + b.branches.size() > 3) continue;
          List<Feature> targets = new ArrayList<>();
          a.branches.forEach(x -> targets.add(x.target));
          b.branches.forEach(x -> targets.add(x.target));
          Coordinate centroid = new Coordinate();
          for (Feature f : targets) {
            centroid.x += f.geometry().getCoordinate().x;
            centroid.y += f.geometry().getCoordinate().y;
          }
          centroid.x /= targets.size();
          centroid.y /= targets.size();
          double spread = 0;
          for (Feature f : targets)
            spread = Math.max(spread, centroid.distance(f.geometry().getCoordinate()));
          if (spread > 150) continue;
          List<Component> others = new ArrayList<>(variant.components);
          others.remove(a);
          others.remove(b);
          if (a.branches.size() == 1 && b.branches.size() == 1)
            for (Component proposed : sharedOnPath(a, b.branches.get(0).target, false))
              if (compatible(proposed, others)) {
                BigDecimal delta =
                    componentScore(a).add(componentScore(b)).subtract(componentScore(proposed));
                if (delta.compareTo(saving) > 0) {
                  saving = delta;
                  best = proposed;
                  ba = a;
                  bb = b;
                }
              }
          for (Root root : List.of(a.root, b.root))
            for (double fraction : new double[] {.45, .70}) {
              Coordinate hub =
                  new Coordinate(
                      root.point.x + (centroid.x - root.point.x) * fraction,
                      root.point.y + (centroid.y - root.point.y) * fraction);
              Component proposed = shared(root, hub, targets);
              if (proposed == null || !compatible(proposed, others)) continue;
              BigDecimal delta =
                  componentScore(a).add(componentScore(b)).subtract(componentScore(proposed));
              if (delta.compareTo(saving) > 0) {
                saving = delta;
                best = proposed;
                ba = a;
                bb = b;
              }
            }
        }
      if (best != null) {
        variant.components.remove(ba);
        variant.components.remove(bb);
        variant.components.add(best);
        improved = true;
        merges++;
      }
    }
  }

  private List<Component> sharedOnPath(Component base, Feature additional, boolean deep) {
    List<Component> found = new ArrayList<>();
    if (base.branches.size() != 1 || base.trunk != null) return found;
    Branch old = base.branches.get(0);
    LineString original = old.line;
    TreeSet<Double> positions = new TreeSet<>();
    positions.add(original.getLength() * .4);
    positions.add(original.getLength() * .7);
    double length = 0;
    for (int i = 1; i < original.getNumPoints() - 1; i++) {
      length += original.getCoordinateN(i).distance(original.getCoordinateN(i - 1));
      positions.add(length);
    }
    var index = new org.locationtech.jts.linearref.LengthIndexedLine(original);
    double nearest = index.project(additional.geometry().getCoordinate());
    for(double shift:new double[]{-32,-16,-8,-4,-2,0,2,4,8,16,32})
      if(nearest+shift>1e-4 && nearest+shift<original.getLength()-1e-4) positions.add(nearest+shift);
    for(int i=1;i<16;i++) positions.add(original.getLength()*i/16.0);
    List<Double> choices = new ArrayList<>(positions);
    choices.sort(Comparator.comparingDouble((Double at)->index.extractPoint(at).distance(additional.geometry().getCoordinate())).thenComparingDouble(at->at));
    int attempted = 0;
    for (double at : choices) {
      if (at < 1e-4 || original.getLength() - at < 1e-4) continue;
      if (attempted++ >= (deep ? 24 : 10)) break;
      LineString trunk = Metric.part(original, 0, at),
          leaf = Metric.part(original, at, original.getLength());
      Coordinate hub = trunk.getEndPoint().getCoordinate();
      LineString other =
          fastSized(
              hub, additional.geometry().getCoordinate(), additional.flow, Set.of(), additional);
      if (other == null && deep && attempted <= 12)
        other =
            sizedRoute(
                hub, additional.geometry().getCoordinate(), additional.flow, Set.of(), additional);
      if (other == null) continue;
      try {
        Component c =
            hydraulic.size(
                base.root,
                hub,
                trunk,
                List.of(new Branch(old.target, leaf, old.dn), new Branch(additional, other, 50)));
        evaluated++;
        if (valid(c)) found.add(c);
      } catch (IllegalArgumentException ignored) {
      }
    }
    return found;
  }

  Component shared(Root root, Coordinate hub, List<Feature> targets) {
    BigDecimal total = BigDecimal.ZERO;
    for (Feature f : targets) total = total.add(f.flow);
    LineString trunk = fastSized(root.point, hub, total, root.incident, null);
    if (trunk == null) return null;
    List<Branch> branches = new ArrayList<>();
    for (Feature f : targets) {
      LineString line = fastSized(hub, f.geometry().getCoordinate(), f.flow, Set.of(), f);
      if (line == null) return null;
      branches.add(new Branch(f, line, 50));
    }
    try {
      Component result = hydraulic.size(root, hub, trunk, branches);
      evaluated++;
      return valid(result) ? result : null;
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  boolean valid(Component c) {
    if (c.root.degree + 1 > 4) return false;
    if (c.trunk != null && !rules.assess(c.trunk, c.trunkDn, c.root.incident, null).valid())
      return false;
    for (Branch b : c.branches)
      if (!rules
          .assess(b.line, b.dn, c.trunk == null ? c.root.incident : Set.of(), b.target)
          .valid()) return false;
    if (c.trunk != null)
      for (Branch b : c.branches)
        if (!Metric.turn(
            c.trunk.getCoordinateN(c.trunk.getNumPoints() - 2), c.hub, b.line.getCoordinateN(1)))
          return false;
    List<LineString> lines = lines(c);
    for (int i = 0; i < lines.size(); i++)
      for (int j = i + 1; j < lines.size(); j++)
        if (!onlySharedEndpoint(lines.get(i), lines.get(j))) return false;
    return true;
  }

  static boolean onlySharedEndpoint(LineString a, LineString b) {
    Geometry cross = a.intersection(b);
    if (cross.isEmpty()) return true;
    if (cross.getDimension() > 0) return false;
    for (Coordinate c : cross.getCoordinates())
      if (Math.min(
                  c.distance(a.getCoordinateN(0)),
                  c.distance(a.getCoordinateN(a.getNumPoints() - 1)))
              > 1e-6
          || Math.min(
                  c.distance(b.getCoordinateN(0)),
                  c.distance(b.getCoordinateN(b.getNumPoints() - 1)))
              > 1e-6) return false;
    return true;
  }

  boolean compatible(Component candidate, List<Component> existing) {
    int added = 1;
    for (Component c : existing) {
      if (candidate.root.point.distance(c.root.point) < 1e-5) {
        if (candidate.root.existingChamber == null || c.root.existingChamber == null) return false;
        added++;
      }
      for (LineString a : lines(candidate))
        for (LineString b : lines(c)) {
          Geometry x = a.intersection(b);
          if (x.isEmpty()) continue;
          if (x.getDimension() > 0) return false;
          for (Coordinate p : x.getCoordinates())
            if (candidate.root.existingChamber == null
                || c.root.existingChamber == null
                || !candidate.root.existingChamber.key.equals(c.root.existingChamber.key)
                || p.distance(candidate.root.point) > 1e-6) return false;
        }
    }
    return candidate.root.degree + added <= 4;
  }

  static List<LineString> lines(Component c) {
    List<LineString> lines = new ArrayList<>();
    if (c.trunk != null) lines.add(c.trunk);
    c.branches.forEach(b -> lines.add(b.line));
    return lines;
  }

  private Variant copy(Variant source) {
    Variant v = new Variant();
    v.components.addAll(source.components);
    v.missing.addAll(source.missing);
    return v;
  }

  private BigDecimal componentCost(Component c) {
    Variant v = new Variant();
    v.components.add(c);
    score(v);
    return v.construction();
  }

  BigDecimal componentScore(Component c) {
    Variant v = new Variant();
    v.components.add(c);
    score(v);
    return v.score;
  }

  public void score(Variant v) {
    v.pipeCost = BigDecimal.ZERO;
    v.chamberCost = BigDecimal.ZERO;
    v.tieCost = BigDecimal.ZERO;
    v.penalty = BigDecimal.ZERO;
    v.length = BigDecimal.ZERO;
    v.tieCount = 0;
    List<String> topology = new ArrayList<>();
    for (Component c : v.components) {
      int max = c.root.existingDn;
      if (c.trunk != null) {
        addLine(v, c.trunk, c.trunkDn, c.root.incident, null);
        max = Math.max(max, c.trunkDn);
        int hubDn = c.trunkDn;
        for (Branch b : c.branches) hubDn = Math.max(hubDn, b.dn);
        v.chamberCost = v.chamberCost.add(CostFunction.chamber(hubDn));
      }
      for (Branch b : c.branches) {
        addLine(v, b.line, b.dn, c.trunk == null ? c.root.incident : Set.of(), b.target);
        if (c.trunk == null) max = Math.max(max, b.dn);
        topology.add(b.target.key + "/" + c.root.key() + "/" + b.dn + "/" + b.line.toText());
      }
      if (c.root.existingChamber == null)
        v.chamberCost = v.chamberCost.add(CostFunction.chamber(max));
      else {
        v.tieCount++;
        v.tieCost = v.tieCost.add(CostFunction.tie());
      }
      if (c.trunk != null) topology.add("trunk/" + c.trunkDn + "/" + c.trunk.toText());
    }
    v.missing.sort(Comparator.comparing(f -> f.key));
    for (Feature f : v.missing) {
      v.penalty = v.penalty.add(CostFunction.penalty(f.flow));
      topology.add("missing/" + f.key);
    }
    v.score = CostFunction.score(v.total(), v.length);
    Collections.sort(topology);
    v.fingerprint = sha(String.join("\n", topology));
  }

  private void addLine(Variant v, LineString line, int dn, Set<String> incident, Feature target) {
    var check = rules.assess(line, dn, incident, target);
    if (!check.valid()) throw new IllegalStateException("Invalid costed route: " + check.error);
    v.length = v.length.add(BigDecimal.valueOf(line.getLength()));
    for (Span part : rules.partition(line, check.spans))
      v.pipeCost = v.pipeCost.add(CostFunction.pipe(part.b - part.a, dn, part.factor));
  }

  private boolean similar(Variant a, Variant b) {
    if (a.components.size() != b.components.size() || a.missing.size() != b.missing.size())
      return false;
    Set<String> ga = new TreeSet<>(), gb = new TreeSet<>();
    for (Component c : a.components) ga.add(group(c));
    for (Component c : b.components) gb.add(group(c));
    if (!ga.equals(gb)) return false;
    List<LineString> la = new ArrayList<>(), lb = new ArrayList<>();
    a.components.forEach(c -> la.addAll(lines(c)));
    b.components.forEach(c -> lb.addAll(lines(c)));
    if (la.isEmpty() || lb.isEmpty()) return la.isEmpty() && lb.isEmpty();
    Geometry x = Metric.GF.createMultiLineString(la.toArray(new LineString[0])),
        y = Metric.GF.createMultiLineString(lb.toArray(new LineString[0]));
    return x.buffer(5).covers(y) && y.buffer(5).covers(x);
  }

  String group(Component c) {
    List<String> ids = new ArrayList<>();
    for (Branch b : c.branches) ids.add(b.target.key + ":" + b.dn);
    Collections.sort(ids);
    return ids
        + "/"
        + c.trunkDn
        + "/"
        + (c.root.existingChamber == null ? "new" : c.root.existingChamber.key);
  }

  private String sha(String value) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder b = new StringBuilder();
      for (byte x : bytes) b.append(String.format("%02x", x));
      return b.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
