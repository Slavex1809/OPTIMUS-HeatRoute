package com.heatroute;

import static org.assertj.core.api.Assertions.*;

import com.heatroute.application.Optimizer;
import com.heatroute.export.GeoJsonExporter;
import com.heatroute.ingest.GeoJsonInput;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class DatasetTest {
  @Test
  void correctedDatasetEndToEnd() throws Exception {
    var snapshot =
        new GeoJsonInput().read(getClass().getResourceAsStream("/static/data/corrected.geojson"));
    assertThat(snapshot.features).hasSize(144);
    assertThat(snapshot.targets).hasSize(17);
    var result = new Optimizer(snapshot, p -> System.out.println(p)).solve();
    assertThat(result.variants).isNotEmpty();
    if (!com.heatroute.constraints.EntryPolicy.nearestOnly())
      for (var variant : result.variants) assertThat(variant.missing).isEmpty();
    Path dir = Path.of("target/evidence");
    Files.createDirectories(dir);
    try (OutputStream out = Files.newOutputStream(dir.resolve("corrected-result.geojson"))) {
      new GeoJsonExporter().write(snapshot, result.variants, out);
    }
    GeoJsonInput.JSON
        .writerWithDefaultPrettyPrinter()
        .writeValue(dir.resolve("corrected-report.json").toFile(), result.report);
    System.out.println(result.report);
    assertThat(result.variants.get(0).components).isNotEmpty();
    for (var v : result.variants) {
      int connected = v.components.stream().mapToInt(c -> c.branches.size()).sum();
      assertThat(connected + v.missing.size()).isEqualTo(snapshot.targets.size());
      new com.heatroute.application.SolutionVerifier(snapshot).verify(v);
    }
  }
}
