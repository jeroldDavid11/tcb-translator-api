package com.tcb.sms.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;

/**
 * Uses AI to:
 *  - determine the likely TCWS signal affecting a province, based on TCWS text
 *  - generate detailed, localized safety suggestion based on PAGASA TCWS
 *    and the cyclone movement.
 *
 * Normal path:
 *   → AI returns suggestion in the requested dialect (e.g. "Bikol", "Ilocano",
 *      "Cebuano", "Waray", etc.).
 *
 * If AI fails or no key:
 *   → fallback suggestion in ENGLISH, but still based on signal level.
 */
@Service
public class ProvinceSignalSuggestionService {

  private static final Logger log = LoggerFactory.getLogger(ProvinceSignalSuggestionService.class);

  @Value("${app.openai.apiKey:}")
  private String apiKey;

  @Value("${app.openai.model:gpt-4o-mini}")
  private String model;

  @Value("${app.openai.timeoutSeconds:25}")
  private int timeoutSec;

  private final WebClient web;
  private final ObjectMapper om = new ObjectMapper();

  public ProvinceSignalSuggestionService(WebClient.Builder builder) {
    this.web = builder
        .baseUrl("https://api.openai.com/v1/chat/completions")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
  }

  /**
   * @param province       e.g. "Benguet"
   * @param dialectOrLang  whatever dialect/label you want AI to use, e.g.
   *                       "Bikol", "Ilocano", "Cebuano", "Waray", "Tagalog",
   *                       or just "English". This comes directly from contacts.
   * @param cyclone        parsed cyclone map (may contain name, movement, etc.)
   * @param tcws           text of "TROPICAL CYCLONE WIND SIGNALS (TCWS) IN EFFECT" section
   *
   * @return Map with keys:
   *   - "signal": Integer (1-5) or null
   *   - "suggestion": String advice (dialect if AI works, English if fallback)
   *   - "_generator": "ai" or a fallback reason
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> analyze(String province,
                                     String dialectOrLang,
                                     Map<String, Object> cyclone,
                                     String tcws) {
    Map<String, Object> out = new HashMap<>();

    // Normalize dialect name for the prompt
    String languageName = safeDialectName(dialectOrLang);

    // If no OpenAI key → fallback to simple English-only suggestions.
    if (apiKey == null || apiKey.isBlank()) {
      Integer signal = fallbackSignalFromTcws(province, tcws);
      out.put("signal", signal);
      out.put("suggestion", fallbackSuggestion(signal));
      out.put("_generator", "rule-fallback-no-key");
      return out;
    }

    String cycloneName = safeStr(cyclone == null ? null : cyclone.get("name"));
    String movement = extractMovement(cyclone);

    String system = """
        You are an emergency weather adviser for the Philippines.

        Your job is to:
        1) Determine the Tropical Cyclone Wind Signal (TCWS) level affecting a given province.
        2) Generate very practical, life-saving advice for people in that province.

        Use Philippine PAGASA guidance for TCWS:

        - TCWS #1: 39–61 km/h winds, light damage possible.
        - TCWS #2: 62–88 km/h winds, minor roof damage possible, light debris.
        - TCWS #3: 89–117 km/h winds, heavy rain, flooding, power outages possible.
        - TCWS #4: 118–184 km/h winds, very dangerous, widespread damage likely.
        - TCWS #5: 185+ km/h winds, catastrophic damage, extreme danger.

        You are given:
        - Province name
        - Cyclone name (if any)
        - Cyclone movement text
        - TCWS text mentioning which areas are under which signal

        LANGUAGE REQUIREMENT (IMPORTANT):
        - Write your advice strictly in this language or dialect: %s
        - Do NOT answer in English unless that is the language or dialect requested.
        - If it is a Philippine dialect (Bikol, Ilocano, Cebuano, Waray, Hiligaynon, etc.),
          use simple, natural phrases in that dialect.

        ADVICE REQUIREMENT:
        - Make the advice detailed and helpful, but still readable in chat:
          * Aim for about 3–7 short lines.
          * Each line should be a clear, actionable step (e.g., "Prepare a go-bag with...").
          * You may use simple bullet-style lines (starting with "•" or "-"), but keep it plain text.
        - You may refer to flooding, landslides, storm surge, strong winds, and power outages
          if those risks are consistent with the TCWS level.
        - Always remind people to follow LGU and PAGASA instructions, including
          contacting their LGU or barangay for help and evacuation information.

        RULES:
        - If the province clearly appears under a certain signal, use that signal (1–5).
        - If the province is not mentioned or not clear, you MAY return signal = null.
        - Do NOT invent new storm behavior not implied by TCWS or movement.

        Return STRICT JSON ONLY:
        {
          "signal": <number or null>,
          "suggestion": "<short localized advice, 3–7 lines>"
        }
        """.formatted(languageName);

    Map<String, Object> userObj = new LinkedHashMap<>();
    userObj.put("province", province);
    userObj.put("language", languageName);
    userObj.put("cyclone_name", cycloneName);
    userObj.put("movement", movement);
    userObj.put("tcws_text", tcws == null ? "" : tcws);

    Map<String, Object> payload = Map.of(
        "model", model,
        "temperature", 0.25,
        "response_format", Map.of("type", "json_object"),
        "messages", List.of(
            Map.of("role", "system", "content", system),
            Map.of("role", "user", "content", toJson(userObj))
        )
    );

    try {
      Map resp = web.post()
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .bodyValue(payload)
          .retrieve()
          .onStatus(HttpStatusCode::isError, r ->
              r.bodyToMono(String.class).defaultIfEmpty("")
                  .map(body -> new RuntimeException("OpenAI error " + r.statusCode() + ": " + body))
          )
          .bodyToMono(Map.class)
          .timeout(Duration.ofSeconds(timeoutSec))
          .block();

      if (resp == null) {
        return fallbackResult(province, tcws, "ai-null");
      }

      List<Map> choices = (List<Map>) resp.get("choices");
      if (choices == null || choices.isEmpty()) {
        return fallbackResult(province, tcws, "ai-empty");
      }

      Map msg = (Map) choices.get(0).get("message");
      if (msg == null) {
        return fallbackResult(province, tcws, "ai-nomsg");
      }

      String content = safeStr(msg.get("content"));
      if (content.isBlank()) {
        return fallbackResult(province, tcws, "ai-blank");
      }

      Map<String, Object> parsed = om.readValue(content, Map.class);

      Object sigObj = parsed.get("signal");
      Integer signal = null;
      if (sigObj instanceof Number n) {
        signal = n.intValue();
      } else if (sigObj instanceof String s && !s.isBlank()) {
        try { signal = Integer.parseInt(s.trim()); } catch (Exception ignored) {}
      }

      String suggestion = safeStr(parsed.get("suggestion"));
      if (suggestion.isBlank()) {
        // AI responded but empty suggestion → English fallback
        suggestion = fallbackSuggestion(signal);
      }

      Map<String, Object> out2 = new HashMap<>();
      out2.put("signal", signal);
      out2.put("suggestion", suggestion);
      out2.put("_generator", "ai");
      return out2;

    } catch (Exception e) {
      log.warn("ProvinceSignalSuggestionService.analyze AI error: {}", e.toString());
      return fallbackResult(province, tcws, "ai-error");
    }
  }

  // ----------------------------------------------------------------------
  // FALLBACKS (ENGLISH-ONLY)
  // ----------------------------------------------------------------------

  private Map<String, Object> fallbackResult(String province,
                                             String tcws,
                                             String reason) {
    Map<String, Object> out = new HashMap<>();
    Integer signal = fallbackSignalFromTcws(province, tcws);
    out.put("signal", signal);
    out.put("suggestion", fallbackSuggestion(signal)); // always EN
    out.put("_generator", reason);
    return out;
  }

  /**
   * Very simple heuristic: looks for "Signal No. X" or "#X" in TCWS text.
   * Does NOT fully localize by province, just returns highest level mentioned.
   */
  private Integer fallbackSignalFromTcws(String province, String tcws) {
    if (tcws == null) return null;
    String t = tcws.toLowerCase(Locale.ROOT);

    int max = 0;
    for (int i = 1; i <= 5; i++) {
      String needle1 = "signal no. " + i;
      String needle2 = "signal #" + i;
      String needle3 = "#" + i;
      if (t.contains(needle1) || t.contains(needle2)) {
        max = Math.max(max, i);
      } else if (t.contains(needle3)) {
        max = Math.max(max, i);
      }
    }
    return max == 0 ? null : max;
  }

  /**
   * English-only fallback advice based on signal.
   * Used whenever AI fails or returns empty suggestion.
   */
  private String fallbackSuggestion(Integer signal) {
    if (signal == null || signal <= 0) {
      return """
        • Monitor official PAGASA and LGU updates.
        • Prepare a basic emergency kit (water, food, flashlight, medicines).
        • Know the nearest evacuation center in your area.""";
    }

    int s = signal;
    return switch (s) {
      case 1 -> """
        • Secure loose items outside (plants, tarps, light roofing).
        • Prepare a small emergency kit (water, food, flashlight, medicines).
        • Keep monitoring PAGASA and your LGU for updated advisories.""";
      case 2 -> """
        • Check and reinforce roofing, windows, and anything that can be blown away.
        • Avoid staying near big trees, electric posts, or old structures.
        • Prepare for possible brownouts and weak mobile signal.
        • Follow LGU guidance if they advise early evacuation in your area.""";
      case 3 -> """
        • Stay indoors in a sturdy house and avoid going out unless it is an emergency.
        • If your area is flood-prone, move important items and family members to higher floors.
        • Prepare for power outages and limited communication.
        • Listen closely to PAGASA and LGU announcements about possible evacuation, especially near rivers and steep slopes.""";
      case 4 -> """
        • If you live in a flood-prone, landslide-prone, or coastal area, coordinate with your LGU about evacuation immediately.
        • Pack a go-bag with documents, medicines, food, water, flashlight, and power bank.
        • Do not attempt to cross fast-moving floodwaters; move to higher, safer ground.
        • Follow LGU instructions strictly and do not wait until the last minute to evacuate.""";
      case 5 -> """
        • Obey mandatory or pre-emptive evacuation orders from your LGU without delay.
        • Move to a safe, sturdy evacuation center or building away from rivers, the coast, and steep slopes.
        • Keep your family together and agree on a meeting point if communication is lost.
        • Rely only on official PAGASA and LGU announcements and do not return home until they declare it safe.""";
      default -> """
        • Follow official instructions from PAGASA and your LGU.
        • Stay in a safe place and keep your emergency supplies ready.
        • Avoid floodwaters and strong winds as much as possible.""";
    };
  }

  // ----------------------------------------------------------------------
  // SMALL HELPERS
  // ----------------------------------------------------------------------

  private String safeDialectName(String dialectOrLang) {
    String s = safeStr(dialectOrLang);
    if (s.isBlank()) return "English";
    return s;
  }

  private String safeStr(Object o) {
    if (o == null) return "";
    String s = String.valueOf(o);
    return s == null ? "" : s.trim();
  }

  @SuppressWarnings("unchecked")
  private String extractMovement(Map<String, Object> cyclone) {
    if (cyclone == null) return "";
    Object mv = cyclone.get("movement");
    if (!(mv instanceof Map<?, ?> m)) return "";
    Object txt = m.get("text");
    return txt == null ? "" : String.valueOf(txt).trim();
  }

  private String toJson(Object o) {
    try { return om.writeValueAsString(o); }
    catch (Exception e) { return "{}"; }
  }
}