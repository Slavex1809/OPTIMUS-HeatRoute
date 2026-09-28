package com.heatroute.persistence;

import com.heatroute.domain.Model.Snapshot;
import java.util.*;

public interface JobStore {
  void save(String id, Map<String, Object> state);

  Map<String, Object> get(String id);

  List<Map<String, Object>> list();

  default void dataset(String id, Snapshot s) {}
}
