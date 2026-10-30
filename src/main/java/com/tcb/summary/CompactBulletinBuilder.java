package com.tcb.summary;

import java.util.*;
import java.util.stream.Collectors;

public class CompactBulletinBuilder {

  @SuppressWarnings("unchecked")
  public String build(Map<String, Object> parsed, int maxAreasPerLevel) {
    if (parsed == null) return "No data.";

    Map<String, Object> cyclone = asMap(parsed.get("cyclone"));
    String name = s(get(asMap(cyclone.get("name")), null)); // name might be a plain String
    if (name == null) name = s(cyclone.get("name"));

    String loc  = s(asMap(cyclone.get("location")).get("text"));
    String mov  = s(asMap(cyclone.get("movement")).get("text"));
    String wind = s(asMap(cyclone.get("winds")).get("max"));
    String raw  = s(parsed.get("rawText"));

    StringBuilder out = new StringBuilder();

    if (name != null && !name.isBlank()) out.append(name); else out.append("Tropical system");
    if (raw != null && raw.toLowerCase(Locale.ROOT).contains("low pressure area")) out.append(" — LPA");

    if (loc != null && !loc.isBlank()) out.append("\nLocation: ").append(trim(loc, 120));
    if (mov != null && !mov.isBlank()) out.append("\nMovement: ").append(trim(mov, 80));
    if (wind != null && !wind.isBlank()) out.append("\nWinds: ").append(wind);

    Map<String, Object> areasByLevel = asMap(parsed.get("areas"));
    String tcws = formatTcws(areasByLevel, maxAreasPerLevel);
    if (!tcws.isBlank()) out.append("\nSignals: ").append(tcws);
    if (raw != null && raw.toLowerCase(Locale.ROOT).contains("rough seas")) {
      out.append("\nSeas: rough to very rough in affected seaboards");
    }
    if (raw != null && raw.toLowerCase(Locale.ROOT).contains("gale")) {
      out.append("\nGusts: strong to gale-force in exposed areas");
    }

    return out.toString().trim();
  }

  private String formatTcws(Map<String, Object> byLevel, int cap) {
    if (byLevel.isEmpty()) return "none";
    List<String> levels = new ArrayList<>(byLevel.keySet());
    levels.sort((a, b) -> {
      try { return Integer.compare(Integer.parseInt(b), Integer.parseInt(a)); }
      catch (Exception e) { return b.compareTo(a); }
    });

    StringBuilder sb = new StringBuilder();
    for (String lvl : levels) {
      Object v = byLevel.get(lvl);
      if (!(v instanceof List<?> list) || list.isEmpty()) continue;

      List<String> names = list.stream()
          .map(it -> s(asMap(it).get("name")))
          .filter(x -> x != null && !x.isBlank())
          .limit(cap)
          .collect(Collectors.toList());

      if (names.isEmpty()) continue;
      if (sb.length() > 0) sb.append(" | ");
      sb.append("#").append(lvl).append(": ").append(String.join(", ", names));
    }
    return sb.length() == 0 ? "none" : sb.toString();
  }

  private Map<String, Object> asMap(Object o) {
    return (o instanceof Map<?,?> m) ? (Map<String, Object>) m : Collections.emptyMap();
  }
  private Object get(Map<String, Object> m, String k) { return (m == null) ? null : m.get(k); }
  private String s(Object o) { return (o == null) ? null : String.valueOf(o); }
  private String trim(String s, int max) { return (s.length() <= max) ? s : s.substring(0, max-1) + "…"; }
}
