package com.tcb.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class JavaPdfParser {

  private static final Logger log = LoggerFactory.getLogger(JavaPdfParser.class);
  private static final int MIN_TEXT_CHARS = 40;

  private final WebClient web;

  public JavaPdfParser(WebClient webClient) {
    this.web = webClient;
  }

  public Mono<Map<String, Object>> parsePdfUrlReactive(String pdfUrl) {
    return web.get()
        .uri(URI.create(pdfUrl))
        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (PDF Fetcher)")
        .accept(MediaType.APPLICATION_PDF, MediaType.ALL)
        .retrieve()
        .bodyToMono(byte[].class)
        .publishOn(Schedulers.boundedElastic())
        .map(this::parseBytesRobust);
  }

  private Map<String, Object> parseBytesRobust(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return result(Map.of(), Map.of(), Map.of(), null, "none", "Empty PDF");
    }

    String s1 = extractText(bytes, true);
    String s2 = extractText(bytes, false);
    String t = chooseBetter(s1, s2);
    if (t == null || t.trim().length() < MIN_TEXT_CHARS) {
      return result(Map.of(), Map.of(), Map.of(), null, "none", "No selectable text");
    }
    t = t.replace("\r\n", "\n").replace('\r', '\n');

    Map<String, Object> cyclone = new LinkedHashMap<>();
    putIfNotNull(cyclone, "name", extractCycloneName(t));
    putIfNotNull(cyclone, "classification", extractClassification(t));
    String loc = extractLocationLine(t);
    if (loc != null) cyclone.put("location", Map.of("text", loc));
    String mov = extractMovementLine(t);
    if (mov != null) cyclone.put("movement", Map.of("text", mov));

    Map<String, List<Map<String, String>>> areas = parseTcwsQuick(t);

    Map<String, String> sections = new LinkedHashMap<>();
    putIfNotBlank(sections, "tcws", slice(t, "TROPICAL CYCLONE WIND SIGNALS (TCWS) IN EFFECT"));
    putIfNotBlank(sections, "hazards_land", slice(t, "OTHER HAZARDS AFFECTING LAND AREAS"));
    putIfNotBlank(sections, "hazards_coastal", slice(t, "HAZARDS AFFECTING COASTAL WATERS"));
    putIfNotBlank(sections, "track_outlook", slice(t, "TRACK AND INTENSITY OUTLOOK"));

    String compact = buildCompact(cyclone, areas, sections);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("cyclone", cyclone);
    out.put("areas", areas);
    out.put("sections", sections);
    out.put("compact", compact);
    out.put("rawText", t);
    out.put("extraction", "text");
    return out;
  }

  private String extractText(byte[] bytes, boolean sortByPosition) {
    try (PDDocument doc = Loader.loadPDF(bytes)) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(sortByPosition);
      return stripper.getText(doc);
    } catch (Exception e) {
      log.debug("PDFBox text extraction failed (sortByPosition={}): {}", sortByPosition, e.toString());
      return null;
    }
  }

  private String chooseBetter(String a, String b) {
    if (a == null) return b;
    if (b == null) return a;
    int sa = score(a), sb = score(b);
    if (sa != sb) return sa > sb ? a : b;
    return (a.length() >= b.length()) ? a : b;
  }

  private int score(String t) {
    int s = 0; String low = t.toLowerCase(Locale.ROOT);
    if (low.contains("tropical cyclone wind signals")) s++;
    if (low.contains("hazards affecting coastal waters")) s++;
    if (low.contains("other hazards affecting land areas")) s++;
    if (low.contains("track and intensity outlook")) s++;
    return s;
  }

  private String extractClassification(String t) {
    Matcher m = Pattern.compile("(?i)\\b(Tropical\\s+Depression|Tropical\\s+Storm|Severe\\s+Tropical\\s+Storm|Typhoon|Low\\s+Pressure\\s+Area)\\b").matcher(t);
    return m.find() ? m.group(1).trim() : null;
  }

  private String extractCycloneName(String t) {
    Matcher m = Pattern.compile("(?im)\\b(Tropical\\s+Depression|Tropical\\s+Storm|Severe\\s+Tropical\\s+Storm|Typhoon|Low\\s+Pressure\\s+Area)\\s+\\(?“?\"?([A-ZÑÄÖÜ\\-]+)\\)?").matcher(t);
    if (m.find()) return m.group(2).toUpperCase(Locale.ROOT);
    m = Pattern.compile("(?i)formerly\\s*[“\"]?([A-Z\\-]+)[”\"]?").matcher(t);
    return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
  }

  private String extractLocationLine(String t) {
    int i = t.toLowerCase(Locale.ROOT).indexOf("location of center");
    if (i < 0) return null;
    String[] lines = t.substring(i).split("\\R");
    StringBuilder sb = new StringBuilder();
    int taken = 0;
    for (int k = 1; k < lines.length && taken < 4; k++) {
      String s = lines[k].trim();
      if (s.isEmpty()) continue;
      if (s.matches("(?i)^(Intensity|Present Movement|Extent of|TROPICAL CYCLONE|OTHER HAZARDS|HAZARDS AFFECTING|TRACK AND INTENSITY|Page \\d+ of \\d+)\\b"))
        break;
      if (sb.length() > 0) sb.append(' ');
      sb.append(s);
      taken++;
      if (s.endsWith(".") || s.endsWith(")")) break;
    }
    String out = sb.toString().trim();
    return out.isBlank() ? null : out;
  }

  private String extractMovementLine(String t) {
    int i = t.toLowerCase(Locale.ROOT).indexOf("present movement");
    if (i < 0) i = t.toLowerCase(Locale.ROOT).indexOf("movement");
    if (i < 0) return null;
    String[] lines = t.substring(i).split("\\R");
    for (int k = 1; k < Math.min(lines.length, 5); k++) {
      String s = lines[k].trim();
      if (!s.isEmpty()) return s;
    }
    return null;
  }

  private String slice(String full, String header) {
    int h = idxHeader(full, header);
    if (h < 0) return "";
    int start = h + header.length();
    int end = full.length();
    for (String stop : new String[]{
        "TROPICAL CYCLONE BULLETIN","OTHER HAZARDS AFFECTING LAND AREAS",
        "HAZARDS AFFECTING COASTAL WATERS","TRACK AND INTENSITY OUTLOOK",
        "Page "
    }) {
      int j = idxHeader(full, stop);
      if (j > h && j < end) end = j;
    }
    String chunk = full.substring(start, end).trim();
    StringBuilder sb = new StringBuilder();
    for (String line : chunk.split("\\R")) {
      String s = line.trim();
      if (s.isEmpty()) continue;
      if (looksLikeBoiler(s)) continue;
      sb.append(s).append('\n');
    }
    return sb.toString().trim();
  }

  private int idxHeader(String t, String header) {
    Pattern p = Pattern.compile("(?im)^\\s*"+Pattern.quote(header)+"\\b.*$");
    Matcher m = p.matcher(t);
    return m.find() ? m.start() : -1;
  }

  private boolean looksLikeBoiler(String s) {
    String low = s.toLowerCase(Locale.ROOT);
    if (low.startsWith("page ") && low.contains(" of ")) return true;
    if (low.startsWith("republic of the philippines")) return true;
    if (low.startsWith("department of science and technology")) return true;
    if (low.contains("philippine atmospheric") && low.contains("services administration")) return true;
    if (low.startsWith("prepared by") || low.startsWith("checked by")) return true;
    return false;
  }

  private Map<String, List<Map<String, String>>> parseTcwsQuick(String t) {
    Map<String, List<Map<String, String>>> out = new LinkedHashMap<>();
    String sec = slice(t, "TROPICAL CYCLONE WIND SIGNALS (TCWS) IN EFFECT");
    if (sec.isBlank()) return out;
    String[] lines = sec.split("\\R");
    String current = null;
    StringBuilder buf = new StringBuilder();
    for (String raw : lines) {
      String line = raw.trim();
      if (line.matches("^[123]\\b.*")) {
        if (current != null && buf.length() > 0) putAreas(out, current, buf.toString());
        current = line.substring(0, 1);
        buf.setLength(0);
        String tail = line.replaceFirst("^\\d+\\s*", "");
        if (!tail.isBlank()) buf.append(' ').append(tail);
        continue;
      }
      if (line.equals("- -") || line.toLowerCase(Locale.ROOT).startsWith("warning lead time")) {
        if (current != null && buf.length() > 0) putAreas(out, current, buf.toString());
        current = null; buf.setLength(0); continue;
      }
      if (current != null) buf.append(' ').append(line);
    }
    if (current != null && buf.length() > 0) putAreas(out, current, buf.toString());
    return out;
  }

  private void putAreas(Map<String, List<Map<String, String>>> out, String lvl, String blob) {
    String cleaned = blob.replaceAll("(?i)^(strong|gale[- ]?force|storm[- ]?force)\\s*winds\\s*", "")
                         .replaceAll("\\s*,\\s*and\\s+", ", ");
    String[] parts = cleaned.split("\\s*,\\s*");
    List<Map<String, String>> list = new ArrayList<>();
    for (String p : parts) {
      String name = p.replaceAll("[)\\]]+$", "").trim();
      if (name.isEmpty()) continue;
      if (name.length() > 120) continue;
      list.add(Map.of("name", name));
    }
    out.put(lvl, list);
  }

  private Map<String, Object> result(Map<String, Object> cyclone,
                                     Map<String, List<Map<String, String>>> areas,
                                     Map<String, String> sections,
                                     String raw, String kind, String note) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("cyclone", cyclone);
    out.put("areas", areas);
    out.put("sections", sections);
    out.put("compact", "");
    out.put("rawText", raw);
    out.put("extraction", kind);
    out.put("note", note);
    return out;
  }

  private String buildCompact(Map<String, Object> cyclone,
                              Map<String, List<Map<String, String>>> areas,
                              Map<String, String> sections) {
    String name = str(cyclone.get("name"));
    String cls  = str(cyclone.get("classification"));
    String loc  = nestedStr(cyclone, "location", "text");
    String mov  = nestedStr(cyclone, "movement", "text");
    StringBuilder sb = new StringBuilder();
    sb.append(name == null ? "Tropical Cyclone" : name);
    if (cls != null) sb.append(" — ").append(cls);
    sb.append('\n').append("Location: ").append(loc == null ? "n/a" : loc);
    sb.append('\n').append("Movement: ").append(mov == null ? "n/a" : mov);

    // Add small TCWS summary if any:
    if (areas != null && !areas.isEmpty()) {
      List<String> lvls = new ArrayList<>(areas.keySet());
      lvls.sort((a,b)->{
        try { return Integer.compare(Integer.parseInt(b), Integer.parseInt(a)); }
        catch(Exception e){ return b.compareTo(a); }
      });
      for (String lvl : lvls) {
        List<Map<String, String>> list = areas.get(lvl);
        if (list==null || list.isEmpty()) continue;
        sb.append('\n').append("Signal #").append(lvl).append(": ");
        String first = list.get(0).getOrDefault("name","");
        int rest = Math.max(0, list.size()-1);
        sb.append(first);
        if (rest>0) sb.append(" +").append(rest);
      }
    }

    // First sentence of track/coastal if present
    String track = firstSentence(sections.get("track_outlook"));
    if (track != null && !track.isBlank()) sb.append('\n').append("Track: ").append(track);
    String sea = firstSentence(sections.get("hazards_coastal"));
    if (sea != null && !sea.isBlank()) sb.append('\n').append("Sea: ").append(sea);

    return sb.toString().trim();
  }

  private String firstSentence(String s) {
    if (s==null) return null;
    String t = s.trim();
    int i = t.indexOf(". ");
    if (i>0) return t.substring(0, i+1);
    return t.length()>220 ? t.substring(0,220)+"…" : t;
  }

  private String nestedStr(Map<String, Object> root, String a, String b) {
    Object x = root.get(a);
    if (!(x instanceof Map)) return null;
    Object y = ((Map<?,?>) x).get(b);
    return y==null?null:String.valueOf(y);
  }
  private static void putIfNotNull(Map<String, Object> m, String k, Object v){ if (v!=null) m.put(k,v); }
  private static void putIfNotBlank(Map<String, String> m, String k, String v){ if (v!=null && !v.isBlank()) m.put(k,v); }
  private static String str(Object o){ return o==null?null:String.valueOf(o); }
}
