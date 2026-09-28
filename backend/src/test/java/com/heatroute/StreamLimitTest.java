package com.heatroute;

import static org.assertj.core.api.Assertions.*;

import com.heatroute.export.GeoJsonExporter;
import com.heatroute.ingest.GeoJsonInput;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class StreamLimitTest {
  @Test
  void resultAllowsExactlyFiveHundredMiB() throws Exception {
    long limit = 500L * 1024 * 1024;
    byte[] block = new byte[1024 * 1024];
    try (var output = new GeoJsonExporter.Limited(OutputStream.nullOutputStream(), limit)) {
      for (int i = 0; i < 500; i++) output.write(block);
      assertThatThrownBy(() -> output.write(1)).isInstanceOf(IOException.class);
    }
  }

  @Test
  @EnabledIfSystemProperty(named = "optimus.large", matches = "true")
  void parseThreeGiBWithoutMaterializingTheFile() throws Exception {
    byte[] prefix =
        "{\"type\":\"FeatureCollection\",\"features\":[]".getBytes(StandardCharsets.UTF_8);
    long size = 3L * 1024 * 1024 * 1024;
    InputStream padding =
        new InputStream() {
          long left = size - prefix.length - 1;

          public int read() {
            if (left == 0) return -1;
            left--;
            return ' ';
          }

          public int read(byte[] b, int o, int n) {
            if (left == 0) return -1;
            int take = (int) Math.min(left, n);
            Arrays.fill(b, o, o + take, (byte) ' ');
            left -= take;
            return take;
          }
        };
    InputStream input =
        new SequenceInputStream(
            new SequenceInputStream(new ByteArrayInputStream(prefix), padding),
            new ByteArrayInputStream(new byte[] {'}'}));
    assertThat(
            new GeoJsonInput()
                .stream(
                    input,
                    f -> {
                      throw new AssertionError();
                    }))
        .isZero();
    System.out.println(
        "PASS exact input bytes="
            + size
            + "; heap="
            + Runtime.getRuntime().maxMemory()
            + "; synthetic JSON padding (not a large-feature benchmark)");
  }
}
