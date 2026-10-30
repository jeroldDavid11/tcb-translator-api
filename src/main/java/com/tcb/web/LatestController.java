package com.tcb.web;

import com.tcb.pagasa.BulletinLocatorService;
import com.tcb.parser.JavaPdfParser;
import com.tcb.translate.OpenAiTranslator;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads PAGASA TCB PDF, parses cyclone data and hazards.
 * Example:
 *  - GET /api/latest-parsed?raw=true
 *  - GET /api/latest-parsed?raw=true&pdf=mirasol
 */
@RestController
@RequestMapping("/api")
public class LatestController {

  private final BulletinLocatorService locator;
  private final JavaPdfParser parser;
  private final WhitelistPdfHostChecker whitelist;
  private final OpenAiTranslator aiTranslator;

  public LatestController(BulletinLocatorService locator,
                          JavaPdfParser parser,
                          WhitelistPdfHostChecker whitelist,
                          OpenAiTranslator aiTranslator) {
    this.locator = locator;
    this.parser = parser;
    this.whitelist = whitelist;
    this.aiTranslator = aiTranslator;
  }

  @GetMapping("/latest-parsed")
  public Mono<Map<String, Object>> latestParsed(
      @RequestParam(name = "raw", defaultValue = "false") boolean raw,
      @RequestParam(name = "pdf", required = false) String pdf
  ) {
    Mono<String> urlMono = (pdf == null || pdf.isBlank())
        ? locator.latestPdfUrl()
        : locator.resolveByName(pdf);

    return urlMono
        .doOnNext(whitelist::ensureAllowed)
        .flatMap(url -> parser.parsePdfUrlReactive(url)
            .map(parsed -> {
              Map<String, Object> out = new HashMap<>();
              out.put("latestUrl", url);
              out.put("cyclone", parsed.get("cyclone"));
              out.put("sections", parsed.get("sections"));
              if (raw) out.put("rawText", parsed.get("rawText"));

              // --- NEW: AI-based short suggestion, with rule-based fallback ---
              String compact = parsed.get("compact") instanceof String
                  ? (String) parsed.get("compact")
                  : "";

              @SuppressWarnings("unchecked")
              Map<String, Object> areas = parsed.get("areas") instanceof Map
                  ? (Map<String, Object>) parsed.get("areas")
                  : Map.of();

              // Try AI suggestion in English
              String suggestion = aiTranslator.suggestActions(compact, areas, "en");
              if (suggestion == null || suggestion.isBlank()) {
                suggestion = defaultSuggestion(areas);
              }

              out.put("suggestion", suggestion);

              return out;
            }));
  }

  // --- Helpers for fallback suggestion (non-AI) ---

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
