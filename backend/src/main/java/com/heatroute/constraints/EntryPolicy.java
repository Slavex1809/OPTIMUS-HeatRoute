package com.heatroute.constraints;

/** User-selected admissible-entry policy; nearest remains an explicit alternative. */
public final class EntryPolicy {
  public static String name() {
    String value = System.getProperty("heatroute.entryPolicy", "cost");
    if (!value.equals("cost") && !value.equals("nearest"))
      throw new IllegalArgumentException("heatroute.entryPolicy must be cost or nearest");
    return value;
  }
  public static boolean nearestOnly() { return name().equals("nearest"); }
  private EntryPolicy() {}
}
