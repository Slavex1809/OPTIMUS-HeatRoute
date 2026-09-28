package com.heatroute.api;

import com.heatroute.application.Jobs;
import com.heatroute.hydraulic.DiameterCatalog;
import com.heatroute.ingest.GeoJsonInput;
import com.heatroute.persistence.JobStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class Api {
  private final Jobs jobs;
  private final JobStore store;

  public Api(Jobs jobs, JobStore store) {
    this.jobs = jobs;
    this.store = store;
  }

  @GetMapping("/capabilities")
  public Map<String, Object> capabilities() {
    return Map.of(
        "name",
        "OPTIMUS HeatRoute",
        "version",
        "2.1.0",
        "entryPolicy", com.heatroute.constraints.EntryPolicy.name(),
        "srid",
        32637,
        "depth",
        false,
        "reconstruction",
        false,
        "maxInputBytes",
        Jobs.MAX_INPUT,
        "maxResultBytes",
        Jobs.MAX_OUTPUT);
  }

  @GetMapping("/catalog")
  public Object catalog() {
    return DiameterCatalog.ALL;
  }

  @GetMapping("/calculations")
  public Object list() {
    return store.list();
  }

  @PostMapping(value = "/calculations", consumes = "multipart/form-data")
  public ResponseEntity<?> create(@RequestPart("file") MultipartFile file) throws IOException {
    try (InputStream in = file.getInputStream()) {
      return ResponseEntity.status(201)
          .body(
              jobs.create(
                  in, Optional.ofNullable(file.getOriginalFilename()).orElse("dataset.geojson")));
    }
  }

  @GetMapping("/calculations/{id}")
  public Object get(@PathVariable UUID id) {
    return store.get(id.toString());
  }

  @PostMapping("/calculations/{id}/run")
  public ResponseEntity<?> run(@PathVariable UUID id) {
    jobs.start(id.toString());
    return ResponseEntity.accepted().body(store.get(id.toString()));
  }

  @GetMapping("/calculations/{id}/result")
  public ResponseEntity<?> result(@PathVariable UUID id) throws IOException {
    if (!store.get(id.toString()).get("status").equals("COMPLETED"))
      throw new IllegalStateException("Result is not ready");
    Path p = jobs.root.resolve(id + ".result.geojson");
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/geo+json"))
        .contentLength(Files.size(p))
        .header("Content-Disposition", "attachment; filename=optimus-" + id + ".geojson")
        .body(new FileSystemResource(p));
  }

  @PostMapping(value = "/datasets/audit", consumes = "multipart/form-data")
  public Object audit(@RequestPart("file") MultipartFile file) throws IOException {
    Map<String, Integer> counts = new TreeMap<>();
    List<String> warnings = new ArrayList<>();
    try (InputStream in = file.getInputStream()) {
      long n =
          new GeoJsonInput()
              .stream(
                  in,
                  f -> {
                    counts.merge(f.type, 1, Integer::sum);
                    if (f.type.equals("restriction")
                        && !GeoJsonInput.RULES.contains(f.restriction)
                        && warnings.size() < 100)
                      warnings.add(
                          "Unsupported optional restriction: " + f.id + " / " + f.restriction);
                  });
      return Map.of(
          "featureCount",
          n,
          "objectTypes",
          counts,
          "warnings",
          warnings,
          "valid",
          counts.getOrDefault("oks_connection_point", 0) > 0
              && counts.getOrDefault("heat_network", 0) > 0,
          "note",
          "Structure and geometry checked; route feasibility is evaluated by calculation");
    }
  }
}
