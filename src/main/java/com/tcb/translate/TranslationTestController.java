package com.tcb.translate;


import com.tcb.pagasa.BulletinLocatorService;
import com.tcb.parser.JavaPdfParser;
import com.tcb.sms.services.ProvinceSignalSuggestionService;
import com.tcb.translate.DialectAiMapper;
import com.tcb.translate.OpenAiThinTranslator;
import com.tcb.translate.OpenAiTranslator;
import com.tcb.web.WhitelistPdfHostChecker;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TranslationTestController {

  private final BulletinLocatorService locator;
  private final JavaPdfParser parser;
  private final WhitelistPdfHostChecker whitelist;
  private final DialectAiMapper dialectMapper;
  private final OpenAiThinTranslator thinTranslator;
  private final ProvinceSignalSuggestionService provinceSuggestionService;
  private final OpenAiTranslator aiTranslator;

  public TranslationTestController(
      BulletinLocatorService locator,
      JavaPdfParser parser,
      WhitelistPdfHostChecker whitelist,
      DialectAiMapper dialectMapper,
      OpenAiThinTranslator thinTranslator,
      ProvinceSignalSuggestionService provinceSuggestionService,
      OpenAiTranslator aiTranslator
  ) {
    this.locator = locator;
    this.parser = parser;
    this.whitelist = whitelist;
    this.dialectMapper = dialectMapper;
    this.thinTranslator = thinTranslator;
    this.provinceSuggestionService = provinceSuggestionService;
    this.aiTranslator = aiTranslator;
  }

  /**
   * Test endpoint – NO SMS is sent.
   *
   * Example:
   *   GET /api/test-translation?province=Benguet&dialect=Bikol
   *   GET /api/test-translation?province=Catanduanes&dialect=Cebuano&pdf=verbena
   */
  @GetMapping("/test-translation")
  public Mono<Map<String, Object>> testTranslation(
      @RequestParam String province,
      @RequestParam String dialect,
      @RequestParam(required = false) String pdf
  ) {
    // Resolve dialect -> normalized code (tagalog, english, cebuano, etc.)
    return dialectMapper.mapDialect(dialect)
        .flatMap(langCode -> {
          Mono<String> pdfMono = (pdf == null || pdf.isBlank())
              ? locator.latestPdfUrl()
              : locator.resolveByName(pdf);

          return pdfMono
              .doOnNext(whitelist::ensureAllowed)
              .flatMap(url ->
                  parser.parsePdfUrlReactive(url)
                      .map(parsed -> buildResponse(province, dialect, langCode, url, parsed))
              );
        });
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> buildResponse(
      String province,
      String dialectInput,
      String languageCode,
      String url,
      Map<String, Object> parsed
  ) {
    Map<String, Object> cyclone = safeMap(parsed.get("cyclone"));
    Map<String, String> sections = safeStringMap(parsed.get("sections"));
    String tcwsOriginal = sections.getOrDefault("tcws", "");

    // 1) Translate cyclone + TCWS into target dialect
    Map<String, Object> translated = thinTranslator.translateCycloneAndTcws(cyclone, tcwsOriginal, languageCode);
    Map<String, Object> translatedCyclone = safeMap(translated.get("cyclone"));
    String translatedTcws = String.valueOf(translated.getOrDefault("tcws", tcwsOriginal));

    // 2) Translate headers (Location / Signal / Cyclone / Movement / Advice)
    Map<String, String> headers = aiTranslator.translateHeaders(languageCode);

    // 3) Province-specific signal & advice (using dialect words for advice)
    String dialectForAdvice = (dialectInput != null && !dialectInput.isBlank())
        ? dialectInput
        : languageCode;

    Map<String, Object> signalInfo =
        provinceSuggestionService.analyze(province, dialectForAdvice, translatedCyclone, translatedTcws);

    // 4) Build output payload
    Map<String, Object> out = new HashMap<>();
    out.put("province", province);
    out.put("dialectInput", dialectInput);
    out.put("languageCode", languageCode);
    out.put("latestUrl", url);
    out.put("translatedCyclone", translatedCyclone);
    out.put("translatedTcws", translatedTcws);
    out.put("headers", headers);
    out.put("signalInfo", signalInfo);
    return out;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> safeMap(Object o) {
    return (o instanceof Map<?, ?> m)
        ? (Map<String, Object>) m
        : new HashMap<>();
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> safeStringMap(Object o) {
    return (o instanceof Map<?, ?> m)
        ? (Map<String, String>) m
        : new HashMap<>();
  }
}