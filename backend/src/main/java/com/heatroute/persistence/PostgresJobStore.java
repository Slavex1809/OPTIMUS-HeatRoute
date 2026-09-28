package com.heatroute.persistence;

import com.heatroute.domain.Model.Snapshot;
import com.heatroute.ingest.GeoJsonInput;
import java.util.*;
import org.locationtech.jts.io.WKBWriter;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("postgres")
public class PostgresJobStore implements JobStore {
  private final JdbcTemplate jdbc;

  public PostgresJobStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void save(String id, Map<String, Object> state) {
    try {
      jdbc.update(
          "INSERT INTO calculation(id,state) VALUES (?::uuid,?::jsonb) ON CONFLICT(id) DO UPDATE"
              + " SET state=excluded.state",
          id,
          GeoJsonInput.JSON.writeValueAsString(state));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> decode(String s) {
    try {
      return GeoJsonInput.JSON.readValue(s, Map.class);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public Map<String, Object> get(String id) {
    var rows =
        jdbc.query(
            "SELECT state::text FROM calculation WHERE id=?::uuid",
            (rs, n) -> decode(rs.getString(1)),
            id);
    if (rows.isEmpty()) throw new NoSuchElementException("Calculation not found");
    return rows.get(0);
  }

  public List<Map<String, Object>> list() {
    return jdbc.query(
        "SELECT state::text FROM calculation ORDER BY created DESC LIMIT 100",
        (rs, n) -> decode(rs.getString(1)));
  }

  @org.springframework.transaction.annotation.Transactional
  public void dataset(String id, Snapshot s) {
    jdbc.update("DELETE FROM input_feature WHERE calculation_id=?::uuid", id);
    jdbc.batchUpdate(
        "INSERT INTO"
            + " input_feature(calculation_id,id_key,original_id,object_type,restriction_type,diameter,flow_tph,geom)"
            + " VALUES (?::uuid,?,?::jsonb,?,?,?,?,ST_GeomFromEWKB(?))",
        s.features,
        500,
        (ps, f) -> {
          ps.setString(1, id);
          ps.setString(2, f.key);
          ps.setString(3, f.id.toString());
          ps.setString(4, f.type);
          ps.setString(5, f.restriction);
          ps.setInt(6, f.diameter);
          ps.setBigDecimal(7, f.flow);
          ps.setBytes(8, new WKBWriter(2, true).write(f.geometry()));
        });
  }
}
