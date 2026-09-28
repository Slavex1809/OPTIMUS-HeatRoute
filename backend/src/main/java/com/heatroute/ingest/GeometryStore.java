package com.heatroute.ingest;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.*;

/** Per-job geometry spool. Metadata/envelopes stay in memory; geometry has a bounded LRU. */
public final class GeometryStore implements AutoCloseable {
  private final FileChannel channel;
  private final Path path;
  private long offset, weight;
  private final LinkedHashMap<Long, Geometry> cache = new LinkedHashMap<>(128, .75f, true);

  public GeometryStore(Path path) throws IOException {
    this.path = path;
    channel =
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE);
  }

  public static final class Ref {
    final long offset;
    final int bytes;

    Ref(long o, int b) {
      offset = o;
      bytes = b;
    }
  }

  public synchronized Ref put(Geometry g) throws IOException {
    byte[] bytes = new WKBWriter(2, true).write(g);
    Ref ref = new Ref(offset, bytes.length);
    ByteBuffer b = ByteBuffer.wrap(bytes);
    while (b.hasRemaining()) channel.write(b, offset + b.position());
    offset += bytes.length;
    return ref;
  }

  public synchronized Geometry get(Ref ref) {
    Geometry value = cache.get(ref.offset);
    if (value != null) return value;
    try {
      ByteBuffer b = ByteBuffer.allocate(ref.bytes);
      while (b.hasRemaining()) {
        int n = channel.read(b, ref.offset + b.position());
        if (n < 0) throw new EOFException("Truncated geometry spool");
      }
      value = new WKBReader().read(b.array());
      cache.put(ref.offset, value);
      weight += Math.max(ref.bytes, value.getNumPoints() * 48L + 256);
      Iterator<Map.Entry<Long, Geometry>> it = cache.entrySet().iterator();
      while ((cache.size() > 2000 || weight > 64L * 1024 * 1024) && cache.size() > 1) {
        var e = it.next();
        weight -=
            Math.max(
                new WKBWriter(2, true).write(e.getValue()).length,
                e.getValue().getNumPoints() * 48L + 256);
        it.remove();
      }
      return value;
    } catch (IOException | ParseException e) {
      throw new IllegalStateException("Geometry spool read failed", e);
    }
  }

  public synchronized void close() throws IOException {
    cache.clear();
    channel.close();
    Files.deleteIfExists(path);
  }
}
