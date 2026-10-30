package com.tcb.web;

import com.tcb.pagasa.BulletinLocatorService;
import com.tcb.parser.JavaPdfParser;
import com.tcb.translate.OpenAiThinTranslator;
import com.tcb.translate.OpenAiTranslator;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LatestTranslateSlimController {

  private final BulletinLocatorService locator;
  private final JavaPdfParser parser;
  private final OpenAiThinTranslator ai;
  private final OpenAiTranslator aiTranslator;
  private final WhitelistPdfHostChecker whitelist;

  public LatestTranslateSlimController(BulletinLocatorService locator,
                                       JavaPdfParser parser,
                                       OpenAiThinTranslator ai,
                                       OpenAiTranslator aiTranslator,
                                       WhitelistPdfHostChecker whitelist) {
    this.locator = locator;
    this.parser = parser;
    this.ai = ai;
    this.aiTranslator = aiTranslator;
    this.whitelist = whitelist;
  }

  /**
   * Only returns the translated "cyclone" and "tcws" fields, plus a short suggestion.
   * Example:
   *   GET /api/latest-translate?lang=ilo
   *   GET /api/latest-translate?lang=ceb
   * Optional:
   *   GET /api/latest-translate?lang=ilo&pdf=mirasol
   */
  @GetMapping("/latest-translate")
  public Mono<Map<String, Object>> latestTranslate(@RequestParam(defaultValue = "en") String lang,
                                                   @RequestParam(required = false) String pdf) {
    String targetLang = normalizeLang(lang);

    Mono<String> urlMono = (pdf == null || pdf.isBlank())
        ? locator.latestPdfUrl()
        : locator.resolveByName(pdf);

    return urlMono
        .doOnNext(whitelist::ensureAllowed)
        .flatMap(url ->
            parser.parsePdfUrlReactive(url).map(parsed -> {
              Map<String, Object> cyclone = safeMap(parsed.get("cyclone"));
              Map<String, String> sections = safeStringMap(parsed.get("sections"));

              // Extract only what we need
              Map<String, Object> slimCyclone = new HashMap<>();
              putIf(slimCyclone, "name",             cyclone.get("name"));
              putIf(slimCyclone, "classification",   cyclone.get("classification"));
              putIf(slimCyclone, "location",         nestedMap(cyclone, "location", "text"));
              putIf(slimCyclone, "movement",         nestedMap(cyclone, "movement", "text"));

              String tcws = sections.getOrDefault("tcws", "");
              String compact = parsed.get("compact") instanceof String
                  ? (String) parsed.get("compact")
                  : "";

              @SuppressWarnings("unchecked")
              Map<String, Object> areas = parsed.get("areas") instanceof Map
                  ? (Map<String, Object>) parsed.get("areas")
                  : Map.of();

              // Ask AI to translate only cyclone/tcws; if it fails, keep originals.
              Map<String, Object> translated = ai.translateCycloneAndTcws(slimCyclone, tcws, targetLang);

              // NEW: ask AI for a very short advice text in the target language
              String suggestion = aiTranslator.suggestActions(compact, areas, targetLang);
              if (suggestion == null || suggestion.isBlank()) {
                // Fallback (English) if AI is not available
                suggestion = defaultSuggestion(areas);
              }

              Map<String, Object> out = new HashMap<>();
              out.put("language", targetLang);
              out.put("generator", translated.getOrDefault("_generator", "ai"));
              out.put("latestUrl", url);
              out.put("cyclone", translated.get("cyclone")); // only this
              out.put("tcws", translated.get("tcws"));       // and this
              out.put("suggestion", suggestion);             // NEW field
              return out;
            })
        );
  }

  // --- helpers ---
  @SuppressWarnings("unchecked")
  private static Map<String, Object> safeMap(Object o) {
    return (o instanceof Map) ? (Map<String, Object>) o : new HashMap<>();
  }
  @SuppressWarnings("unchecked")
  private static Map<String, String> safeStringMap(Object o) {
    return (o instanceof Map) ? (Map<String, String>) o : new HashMap<>();
  }
  private static void putIf(Map<String, Object> m, String k, Object v) {
    if (v == null) return;
    if (v instanceof Map) {
      Map<?,?> mv = (Map<?,?>) v;
      if (mv.containsKey("text")) {
        Map<String, Object> onlyText = new HashMap<>();
        onlyText.put("text", mv.get("text"));
        m.put(k, onlyText);
        return;
      }
    }
    m.put(k, v);
  }
  @SuppressWarnings("unchecked")
  private static Map<String, Object> nestedMap(Map<String, Object> root, String key, String onlyField) {
    Object x = root.get(key);
    if (!(x instanceof Map)) return null;
    Object val = ((Map<?, ?>) x).get(onlyField);
    if (val == null) return null;
    Map<String, Object> m = new HashMap<>();
    m.put("text", val);
    return m;
  }
  private static String normalizeLang(String lang) {
    if (lang == null || lang.isBlank()) return "en";
    String s = lang.trim().toLowerCase();
    // keep as-is if you need specific PH languages (ilo, ceb, war, hil, etc.)
    return switch (s) {
      case "tagalog", "filipino", "tl", "en-ph" -> "tl";
      default -> s;
    };
  }

  private static String defaultSuggestion(Map<String, Object> areas) {
    int max = highestSignal(areas);
    return switch (max) {
      case 3 -> "Signal #3 areas: stay indoors, secure your home, avoid travel, and follow LGU and PAGASA advisories.";
      case 2 -> "Signal #2 areas: limit travel, secure loose objects outdoors, and keep monitoring PAGASA and LGU updates.";
      case 1 -> "Signal #1 areas: be alert for rains and gusty winds, check for possible floods or landslides, and monitor advisories.";
      default -> "Monitor official PAGASA bulletins and LGU announcements for further updates.";
    };
  }

  private static int highestSignal(Map<String, Object> areas) {
    if (areas == null || areas.isEmpty()) return 0;
    int max = 0;
    for (Object key : areas.keySet()) {
      try {
        int n = Integer.parseInt(String.valueOf(key).trim());
        if (n > max) max = n;
      } catch (NumberFormatException ignored) {}
    }
    return max;
  }
}
