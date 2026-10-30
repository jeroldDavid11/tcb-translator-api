package com.tcb.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OpenAiTranslator {

  @Value("${app.openai.apiKey:}")
  private String apiKey;

  @Value("${app.openai.model:gpt-4o-mini}")
  private String model;

  @Value("${app.openai.timeoutSeconds:30}")
  private int timeoutSec;

  private final WebClient web = WebClient.builder()
      .baseUrl("https://api.openai.com/v1/chat/completions")
      .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
      .build();

  private final ObjectMapper om = new ObjectMapper();

  // ----------------------------------------------------------------------
  // 1) TRANSLATE A SHORT “COMPACT” SUMMARY
  // ----------------------------------------------------------------------
  /** Translate a very short “compact” text to the target language (ilocano/ceb/etc.). */
  public String translateCompact(String compact, String lang) {
    if (apiKey == null || apiKey.isBlank()) return null;

    String sys = """
      You translate brief Philippine weather bulletins.
      Output only in the requested language. Be concise, accurate, neutral.
      Keep place names unchanged.
      """;

    String user = """
      Target language: %s

      Source:
      %s
      """.formatted(lang == null ? "en" : lang, compact == null ? "" : compact);

    try {
      Map<String, Object> payload = Map.of(
          "model", model,
          "messages", List.of(
              Map.of("role", "system", "content", sys),
              Map.of("role", "user", "content", user)
          ),
          "temperature", 0.1
      );

      Map resp = web.post()
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(Map.class)
          .timeout(Duration.ofSeconds(timeoutSec))
          .block();

      if (resp == null) return null;
      List<Map> choices = (List<Map>) resp.get("choices");
      if (choices == null || choices.isEmpty()) return null;
      Map msg = (Map) choices.get(0).get("message");
      return msg == null ? null : (String) msg.get("content");

    } catch (Exception e) {
      return null;
    }
  }

  // ----------------------------------------------------------------------
  // 2) SHORT ACTION SUGGESTION (YOU ALREADY USE THIS)
  // ----------------------------------------------------------------------
  /**
   * Ask AI to generate a short “what to do” suggestion based on:
   *  - compact bulletin summary (English)
   *  - TCWS levels (areasByLevel)
   *  - target language (e.g. en, ilo, ceb)
   *
   * Returns null if API key is missing or call fails.
   */
  @SuppressWarnings("unchecked")
  public String suggestActions(String compact, Map<String, Object> areasByLevel, String lang) {
    if (apiKey == null || apiKey.isBlank()) return null;

    String highestSignal = "none";
    if (areasByLevel != null && !areasByLevel.isEmpty()) {
      int max = 0;
      for (Object key : areasByLevel.keySet()) {
        try {
          int n = Integer.parseInt(String.valueOf(key).trim());
          if (n > max) max = n;
        } catch (NumberFormatException ignored) {}
      }
      highestSignal = (max == 0) ? "none" : ("#" + max);
    }

    String sys = """
      You are an assistant for Philippine tropical cyclone bulletins.
      Based on the storm summary and highest Tropical Cyclone Wind Signal (TCWS),
      give very short practical advice for the general public.
      
      Requirements:
      - Use the requested language.
      - 1 to 3 sentences only.
      - No markdown, no bullet points.
      - No technical jargon; use simple words.
      - Do NOT invent new details about the storm; just general safety behavior
        (stay indoors, avoid travel, watch for floods, follow LGU and PAGASA).
      """;

    String user = """
      Target language: %s
      Highest TCWS signal: %s

      Bulletin summary:
      %s
      """.formatted(
          (lang == null || lang.isBlank()) ? "en" : lang,
          highestSignal,
          compact == null ? "" : compact
      );

    try {
      Map<String, Object> payload = Map.of(
          "model", model,
          "messages", List.of(
              Map.of("role", "system", "content", sys),
              Map.of("role", "user", "content", user)
          ),
          "temperature", 0.2
      );

      Map resp = web.post()
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(Map.class)
          .timeout(Duration.ofSeconds(timeoutSec))
          .block();

      if (resp == null) return null;
      List<Map> choices = (List<Map>) resp.get("choices");
      if (choices == null || choices.isEmpty()) return null;
      Map msg = (Map) choices.get(0).get("message");
      return msg == null ? null : (String) msg.get("content");

    } catch (Exception e) {
      return null;
    }
  }

  // ----------------------------------------------------------------------
  // 3) NEW: TRANSLATE HEADERS (Location / Signal / Cyclone / Movement / Advice)
  // ----------------------------------------------------------------------
  /**
   * Translate header labels (Location, Signal, Cyclone, Movement, Advice)
   * into the target dialect.
   *
   * @param dialectCode normalized dialect from DialectAiMapper
   *                    (tagalog, english, cebuano, ilocano, hiligaynon, waray,
   *                     kapampangan, bikol, pangasinan, maranao, maguindanaon,
   *                     tausug, chavacano, kinaray-a)
   *
   * @return map with keys: location, signal, cyclone, movement, advice
   */
  @SuppressWarnings("unchecked")
  public Map<String, String> translateHeaders(String dialectCode) {
    Map<String, String> english = defaultEnglishHeaders();

    // No API key → simple fallback (Tagalog gets Tagalog headers, others English)
    if (apiKey == null || apiKey.isBlank()) {
      return fallbackHeaders(dialectCode);
    }

    String langName = languageNameForDialect(dialectCode);

    String system = """
      You translate ONLY short UI labels for a weather alert SMS.

      Translate these English labels into the target language or dialect:
      - Location
      - Signal
      - Cyclone
      - Movement
      - Advice

      RULES:
      - Return STRICT JSON ONLY with keys: location, signal, cyclone, movement, advice.
      - Do NOT add any extra keys or text.
      - Use natural, short words people actually use in that language.
      - Keep the meaning:
        * Location = place / area of the person
        * Signal   = tropical cyclone warning level (TCWS)
        * Cyclone  = tropical cyclone / bagyo name
        * Movement = direction + speed of the cyclone
        * Advice   = safety instructions or guidance
      - Never include any province or city names (e.g., do NOT output "Cebu").
      """;

    String user = """
      Target language or dialect: %s

      Please translate the 5 headers now.
      """.formatted(langName);

    try {
      Map<String, Object> payload = Map.of(
          "model", model,
          "temperature", 0.2,
          "response_format", Map.of("type", "json_object"),
          "messages", List.of(
              Map.of("role", "system", "content", system),
              Map.of("role", "user", "content", user)
          )
      );

      Map resp = web.post()
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(Map.class)
          .timeout(Duration.ofSeconds(timeoutSec))
          .block();

      if (resp == null) return fallbackHeaders(dialectCode);
      List<Map> choices = (List<Map>) resp.get("choices");
      if (choices == null || choices.isEmpty()) return fallbackHeaders(dialectCode);
      Map msg = (Map) choices.get(0).get("message");
      if (msg == null) return fallbackHeaders(dialectCode);

      String content = String.valueOf(msg.get("content"));
      if (content == null || content.isBlank()) return fallbackHeaders(dialectCode);

      Map<String, Object> parsed = om.readValue(content, Map.class);

      Map<String, String> out = new HashMap<>();
      out.put("location", strOrDefault(parsed.get("location"), english.get("location")));
      out.put("signal",   strOrDefault(parsed.get("signal"),   english.get("signal")));
      out.put("cyclone",  strOrDefault(parsed.get("cyclone"),  english.get("cyclone")));
      out.put("movement", strOrDefault(parsed.get("movement"), english.get("movement")));
      out.put("advice",   strOrDefault(parsed.get("advice"),   english.get("advice")));
      return out;

    } catch (Exception e) {
      return fallbackHeaders(dialectCode);
    }
  }

  // ----------------------------------------------------------------------
  // HELPER METHODS FOR HEADERS
  // ----------------------------------------------------------------------
  private Map<String, String> defaultEnglishHeaders() {
    Map<String, String> m = new HashMap<>();
    m.put("location", "Location");
    m.put("signal", "Signal");
    m.put("cyclone", "Cyclone");
    m.put("movement", "Movement");
    m.put("advice", "Advice");
    return m;
  }

  /**
   * Simple fallback: Tagalog gets Tagalog labels; others mostly English.
   */
  private Map<String, String> fallbackHeaders(String dialectCode) {
    String code = (dialectCode == null ? "" : dialectCode.trim().toLowerCase());

    // Tagalog only – because it's safest
    if (code.startsWith("tagalog") || code.equals("tl")) {
      Map<String, String> m = new HashMap<>();
      m.put("location", "Lokasyon");
      m.put("signal",   "Signal");
      m.put("cyclone",  "Bagyo");
      m.put("movement", "Galaw");
      m.put("advice",   "Payo");
      return m;
    }

    // Everyone else → English headers (body text is still localized)
    return defaultEnglishHeaders();
  }

  /**
   * Map dialect code to a human-readable language name for the prompt.
   */
  private String languageNameForDialect(String dialectCode) {
    if (dialectCode == null || dialectCode.isBlank()) return "English";
    String s = dialectCode.trim().toLowerCase();

    return switch (s) {
      case "tagalog"      -> "Tagalog";
      case "english"      -> "English";
      case "cebuano"      -> "Cebuano (Bisaya)";
      case "ilocano"      -> "Ilocano";
      case "hiligaynon"   -> "Hiligaynon";
      case "waray"        -> "Waray-Waray";
      case "kapampangan"  -> "Kapampangan";
      case "bikol"        -> "Bikol";
      case "pangasinan"   -> "Pangasinan";
      case "maranao"      -> "Maranao";
      case "maguindanaon" -> "Maguindanaon";
      case "tausug"       -> "Tausug";
      case "chavacano"    -> "Chavacano";
      case "kinaray-a"    -> "Kinaray-a";
      default             -> "English";
    };
  }

  private String strOrDefault(Object o, String def) {
    if (o == null) return def;
    String s = String.valueOf(o).trim();
    return s.isEmpty() ? def : s;
  }
}