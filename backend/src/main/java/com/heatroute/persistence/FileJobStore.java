package com.heatroute.persistence;

import com.heatroute.ingest.GeoJsonInput;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!postgres")
public class FileJobStore implements JobStore {
  private final Path root;

  public FileJobStore(@Value("${optimus.storage}") String root) throws IOException {
    this.root = Path.of(root, "jobs");
    Files.createDirectories(this.root);
  }

  public synchronized void save(String id, Map<String, Object> state) {
    try {
      Path tmp = Files.createTempFile(root, "job-", ".tmp");
      GeoJsonInput.JSON.writeValue(tmp.toFile(), state);
      Files.move(
          tmp,
          root.resolve(id + ".json"),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  public synchronized Map<String, Object> get(String id) {
    try {
      return GeoJsonInput.JSON.readValue(root.resolve(id + ".json").toFile(), Map.class);
    } catch (IOException e) {
      throw new NoSuchElementException("Calculation not found");
    }
  }

  public List<Map<String, Object>> list() {
    List<Map<String, Object>> list = new ArrayList<>();
    try (var stream = Files.list(root)) {
      stream
          .filter(p -> p.toString().endsWith(".json"))
          .forEach(p -> list.add(get(p.getFileName().toString().replace(".json", ""))));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    list.sort(Comparator.comparing(m -> m.get("created").toString(), Comparator.reverseOrder()));
    return list.subList(0, Math.min(100, list.size()));
  }
}
