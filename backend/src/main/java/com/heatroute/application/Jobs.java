package com.heatroute.application;

import com.heatroute.export.GeoJsonExporter;
import com.heatroute.ingest.GeoJsonInput;
import com.heatroute.persistence.JobStore;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class Jobs {
  public static final long MAX_INPUT = 3L * 1024 * 1024 * 1024, MAX_OUTPUT = 500L * 1024 * 1024;
  public final Path root;
  private final JobStore store;
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(50));
  private final Semaphore uploads = new Semaphore(2, true),
      largeCalculations = new Semaphore(1, true);

  public Jobs(JobStore store, @Value("${optimus.storage}") String root) throws IOException {
    this.store = store;
    this.root = Path.of(root);
    Files.createDirectories(this.root);
  }

  public Map<String, Object> create(InputStream input, String filename) throws IOException {
    uploads.acquireUninterruptibly();
    String id = UUID.randomUUID().toString();
    Path file = root.resolve(id + ".input.geojson");
    try {
      MessageDigest digest;
      try { digest = MessageDigest.getInstance("SHA-256"); }
      catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
      long bytes = 0;
      try (OutputStream out = Files.newOutputStream(file)) {
        byte[] buffer = new byte[1024 * 1024];
        int n;
        while ((n = input.read(buffer)) != -1) {
          bytes += n;
          if (bytes > MAX_INPUT) throw new IOException("Input exceeds 3 GiB");
          out.write(buffer, 0, n);
          digest.update(buffer, 0, n);
        }
      }
      Map<String,Integer> counts = new TreeMap<>();
      List<String> warnings = new ArrayList<>();
      long count;
      try (InputStream in = Files.newInputStream(file)) {
        count = new GeoJsonInput().stream(in, f -> {
          counts.merge(f.type, 1, Integer::sum);
          if (f.type.equals("restriction") && !GeoJsonInput.RULES.contains(f.restriction) && warnings.size() < 100)
            warnings.add("Unsupported optional restriction: " + f.id + " / " + f.restriction);
        });
      }
      if (counts.getOrDefault("oks_connection_point",0)==0 || counts.getOrDefault("heat_network",0)==0)
        throw new IOException("Required heat_network and oks_connection_point");
      StringBuilder hash = new StringBuilder();
      for (byte b : digest.digest()) hash.append(String.format("%02x",b));
      Map<String, Object> state = new LinkedHashMap<>();
      state.put("inputSha256", hash.toString());
      state.put("audit", Map.of("featureCount",count,"objectTypes",counts,"warnings",warnings,"valid",true));
      state.put("id", id);
      state.put("filename", filename);
      state.put("created", Instant.now().toString());
      state.put("status", "IMPORTED");
      state.put("inputBytes", bytes);
      store.save(id, state);
      return state;
    } catch (Exception e) {
      Files.deleteIfExists(file);
      throw e;
    } finally {
      uploads.release();
    }
  }

  public synchronized void start(String id) {
    Map<String, Object> state = store.get(id);
    if (!Set.of("IMPORTED", "FAILED").contains(state.get("status")))
      throw new IllegalStateException("Already running or completed");
    state.put("status", "QUEUED");
    state.remove("error");
    store.save(id, state);
    try {
      workers.execute(() -> calculate(id, state));
    } catch (RejectedExecutionException e) {
      state.put("status", "IMPORTED");
      store.save(id, state);
      throw new IllegalStateException("Queue full; retry later");
    }
  }

  private void calculate(String id, Map<String, Object> state) {
    Path tmp = root.resolve(id + ".result.tmp");
    boolean large = ((Number) state.get("inputBytes")).longValue() > 256L * 1024 * 1024;
    if (large) largeCalculations.acquireUninterruptibly();
    try {
      state.put("status", "RUNNING");
      state.put("progress", Map.of("phase", "VALIDATING"));
      store.save(id, state);
      try (var snapshot =
          new GeoJsonInput()
              .read(
                  Files.newInputStream(root.resolve(id + ".input.geojson")),
                  root.resolve(id + ".geometry.spool"))) {
        for (var f : snapshot.features)
          if (f.type.equals("restriction") && !GeoJsonInput.RULES.contains(f.restriction))
            snapshot.warnings.add(
                "Unsupported optional restriction " + f.id + ": " + f.restriction);
        store.dataset(id, snapshot);
        var result =
            new Optimizer(
                    snapshot,
                    p -> {
                      state.put("progress", p);
                      store.save(id, state);
                    })
                .solve();
        try (OutputStream out =
            new GeoJsonExporter.Limited(Files.newOutputStream(tmp), MAX_OUTPUT)) {
          new GeoJsonExporter().write(snapshot, result.variants, out);
        }
        Files.move(
            tmp,
            root.resolve(id + ".result.geojson"),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
        state.put("status", "COMPLETED");
        result.report.put("inputSha256", state.get("inputSha256"));
        state.put("report", result.report);
        state.put("finished", Instant.now().toString());
        store.save(id, state);
      }
    } catch (Exception e) {
      try {
        Files.deleteIfExists(tmp);
      } catch (IOException ignored) {
      }
      state.put("status", "FAILED");
      state.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
      store.save(id, state);
    } finally {
      if (large) largeCalculations.release();
    }
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recover() {
    for (var state : store.list())
      if (Set.of("RUNNING", "QUEUED").contains(state.get("status"))) {
        state.put("status", "FAILED");
        state.put("error", "Interrupted by restart; resubmit calculation");
        store.save(state.get("id").toString(), state);
      }
  }

  @PreDestroy
  public void close() {
    workers.shutdown();
  }
}
