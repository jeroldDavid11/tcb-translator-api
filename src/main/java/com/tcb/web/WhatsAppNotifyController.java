package com.tcb.web;

import com.tcb.pagasa.BulletinLocatorService;
import com.tcb.parser.JavaPdfParser;
import com.tcb.sms.services.ClickSendSmsService;
import com.tcb.sms.services.ProvinceSignalSuggestionService;
import com.tcb.translate.DialectAiMapper;
import com.tcb.translate.OpenAiThinTranslator;
import com.tcb.translate.OpenAiTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;

/**
 * Sends SMS notifications via ClickSend, using the same logic
 * you used for WhatsApp (dialect + province + TCWS + AI suggestions).
 *
 * Modes:
 * 1) Manual list:
 *    POST /api/notify-whatsapp?pdf=uwan
 *    or
 *    POST /api/notify-smsapp?pdf=uwan
 *    Body:
 *    [
 *      { "id":1, "mobileNumber":"0908...", "province":"Benguet", "dialect":"Ilocano" },
 *      { "id":2, "mobileNumber":"0917...", "province":"Leyte",   "dialect":"Waray-Waray" }
 *    ]
 *
 * 2) Auto-fetch contacts (no body):
 *    POST /api/notify-whatsapp?pdf=uwan
 *    or
 *    POST /api/notify-smsapp?pdf=uwan
 *    → calls http://lokal.ap-southeast-2.elasticbeanstalk.com/api/contacts
 *
 * 3) Optional limit:
 *    &limit=5  → only send to first 5 contacts (helps avoid API Gateway timeout)
 */
@RestController
@RequestMapping("/api")
public class WhatsAppNotifyController { // name kept for now

  private static final Logger log = LoggerFactory.getLogger(WhatsAppNotifyController.class);

  private final ClickSendSmsService smsService;
  private final DialectAiMapper dialectMapper;
  private final ProvinceSignalSuggestionService provinceSuggestionService;
  private final BulletinLocatorService locator;
  private final JavaPdfParser parser;
  private final WhitelistPdfHostChecker whitelist;
  private final OpenAiThinTranslator thinTranslator;
  private final OpenAiTranslator aiTranslator;
  private final WebClient webClient;

  public WhatsAppNotifyController(
      ClickSendSmsService smsService,
      DialectAiMapper dialectMapper,
      ProvinceSignalSuggestionService provinceSuggestionService,
      BulletinLocatorService locator,
      JavaPdfParser parser,
      WhitelistPdfHostChecker whitelist,
      OpenAiThinTranslator thinTranslator,
      OpenAiTranslator aiTranslator,
      WebClient webClient
  ) {
    this.smsService = smsService;
    this.dialectMapper = dialectMapper;
    this.provinceSuggestionService = provinceSuggestionService;
    this.locator = locator;
    this.parser = parser;
    this.whitelist = whitelist;
    this.thinTranslator = thinTranslator;
    this.aiTranslator = aiTranslator;
    this.webClient = webClient;
  }

  // -----------------------------------------------------------
  // MAIN ENDPOINT – works with or without body
  // -----------------------------------------------------------
  @PostMapping({"/notify-whatsapp", "/notify-smsapp"})
  public Mono<Map<String, Object>> notifySms(
      @RequestParam(name = "pdf", required = false) String pdf,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestBody(required = false) List<Map<String, Object>> contactsBody
  ) {
    String finalPdf = (pdf == null || pdf.isBlank()) ? null : pdf;

    Mono<List<Map<String, Object>>> contactsMono;

    // If contacts are passed in body → use them
    if (contactsBody != null && !contactsBody.isEmpty()) {
      contactsMono = Mono.just(contactsBody);
    } else {
      // Else → fetch external contacts list
      contactsMono = webClient.get()
          .uri("http://lokal.ap-southeast-2.elasticbeanstalk.com/api/contacts")
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .bodyToFlux(Map.class)
          .cast((Class<Map<String, Object>>)(Class<?>)Map.class)
          .collectList();
    }

    return contactsMono.flatMap(contacts -> {
      // Apply optional limit to avoid long-running Lambda
      Flux<Map<String, Object>> contactFlux = Flux.fromIterable(contacts);
      if (limit != null && limit > 0) {
        contactFlux = contactFlux.take(limit);
      }

      return contactFlux
          .flatMap(contact ->
              processContact(contact, finalPdf)
                  .onErrorResume(e -> {
                    log.warn("SMS notify failed for {} → {}", contact, e.toString());
                    Map<String, Object> err = new HashMap<>();
                    err.put("status", "error");
                    err.put("contact", contact);
                    err.put("errorMessage",
                        e.getClass().getSimpleName() +
                            (e.getMessage() != null ? ": " + e.getMessage() : "")
                    );
                    return Mono.just(err);
                  })
          )
          .collectList()
          .map(results -> Map.of(
              "count", results.size(),
              "results", results
          ));
    });
  }

  // -----------------------------------------------------------
  // SIMPLE TEST ENDPOINT (no AI, no PDF, just ClickSend)
  // -----------------------------------------------------------
  @GetMapping("/test-sms")
  public Mono<Map<String, Object>> testSms(@RequestParam("to") String to) {
    String normalized = normalizePhone(to);
    String msg = "Test SMS from PAGASA TCB notifier (ClickSend).";

    return Mono.fromCallable(() -> smsService.sendSms(normalized, msg))
        .subscribeOn(Schedulers.boundedElastic())
        .map(messageId -> Map.of(
            "status", "sent",
            "messageId", messageId,
            "to", normalized,
            "message", msg
        ));
  }

  // -----------------------------------------------------------
  // PROCESS ONE CONTACT (Reactive wrapper)
  // -----------------------------------------------------------
  private Mono<Map<String, Object>> processContact(Map<String, Object> contact, String pdf) {
    String phoneRaw = asStr(contact.get("mobileNumber"));
    String province = asStr(contact.get("province"), "your area");
    String dialect = asStr(contact.get("dialect"), null);

    if (phoneRaw == null) {
      return Mono.error(new IllegalArgumentException("mobileNumber required: " + contact));
    }

    String phone = normalizePhone(phoneRaw);
    log.info("Normalized phone for contact {} -> {}", contact.get("id"), phone);

    return dialectMapper.mapDialect(dialect)
        .flatMap(lang -> {

          // 1) PDF resolution (latest or by name)
          Mono<String> pdfMono = (pdf == null)
              ? locator.latestPdfUrl()
              : locator.resolveByName(pdf);

          return pdfMono
              .doOnNext(whitelist::ensureAllowed)
              .flatMap(url ->
                  parser.parsePdfUrlReactive(url)
                      .flatMap(parsed ->
                          Mono.fromCallable(() ->
                              processWithAiAndSend(
                                  contact, phone, province,
                                  dialect, lang, url, parsed
                              )
                          ).subscribeOn(Schedulers.boundedElastic())
                      )
              );
        });
  }

  // -----------------------------------------------------------
  // BLOCKING PART – AI + send SMS via ClickSend
  // -----------------------------------------------------------
  @SuppressWarnings("unchecked")
  private Map<String, Object> processWithAiAndSend(
      Map<String, Object> contact,
      String phone,
      String province,
      String dialect,
      String lang,
      String url,
      Map<String, Object> parsed
  ) {
    Map<String, Object> cyclone = safeMap(parsed.get("cyclone"));
    Map<String, String> sections = safeStringMap(parsed.get("sections"));
    String tcwsOriginal = asStr(sections.get("tcws"));

    // 1) AI translate cyclone + tcws into requested dialect
    Map<String, Object> translated = thinTranslator.translateCycloneAndTcws(cyclone, tcwsOriginal, lang);
    Map<String, Object> cycl = safeMap(translated.get("cyclone"));
    String tcwsForAi = asStr(translated.get("tcws"), tcwsOriginal);

    String cycloneName = asStr(cycl.get("name"), "Tropical system");
    String movement = extractMovement(cycl);

    // 2) AI province-specific safety suggestion (may return null/0 for no signal)
    String dialectForAdvice = (dialect != null && !dialect.isBlank()) ? dialect : lang;

    Map<String, Object> sigInfo =
        provinceSuggestionService.analyze(province, dialectForAdvice, cycl, tcwsForAi);

    Integer signal = extractSignal(sigInfo.get("signal"));
    String suggestion = asStr(sigInfo.get("suggestion"));

    // 3) Build SMS message body
    String body = buildSmsMessage(
        province,
        dialect,
        cycloneName,
        movement,
        signal,
        suggestion,
        lang
    );

    // 4) Send via ClickSend
    String messageId = smsService.sendSms(phone, body);

    Map<String, Object> out = new HashMap<>();
    out.put("status", "sent");
    out.put("sid", messageId);
    out.put("to", phone);
    out.put("province", province);
    out.put("dialect", dialect);
    out.put("language", lang);
    out.put("signal", signal);
    out.put("message", body);
    out.put("latestUrl", url);
    return out;
  }

  // -----------------------------------------------------------
  // BUILD SMS MESSAGE
  // -----------------------------------------------------------
  private String buildSmsMessage(
      String province,
      String dialect,
      String cycloneName,
      String movement,
      Integer signal,
      String suggestion,
      String lang
  ) {
    // Get translated headers (Location, Signal, Cyclone, Movement, Advice)
    Map<String, String> headers = aiTranslator.translateHeaders(
        (lang == null || lang.isBlank()) ? "en" : lang
    );

    StringBuilder b = new StringBuilder();

    // Location line
    b.append(headers.getOrDefault("location", "Location"))
        .append(": ")
        .append(province)
        .append("\n");

    // Optional dialect info (as stored in contacts)
    if (dialect != null && !dialect.isBlank()) {
      b.append("Dialect: ").append(dialect).append("\n");
    }

    // Signal line:
    // - If signal > 0 -> "Signal: #2"
    // - If no signal/null/0 -> "Signal: No Signal in your area"
    if (signal != null && signal > 0) {
      b.append(headers.getOrDefault("signal", "Signal"))
          .append(": #")
          .append(signal)
          .append("\n");
    } else {
      b.append(headers.getOrDefault("signal", "Signal"))
          .append(": No Signal in your area")
          .append("\n");
    }

    // Cyclone name
    b.append(headers.getOrDefault("cyclone", "Cyclone"))
        .append(": ")
        .append(cycloneName)
        .append("\n");

    // Movement (shortened)
    if (movement != null && !movement.isBlank()) {
      b.append(headers.getOrDefault("movement", "Movement"))
          .append(": ")
          .append(shorten(movement, 200))
          .append("\n");
    }

    // Advice / suggestion (shortened, but keeps important actions)
    if (suggestion != null && !suggestion.isBlank()) {
      b.append(headers.getOrDefault("advice", "Advice"))
          .append(": ")
          .append(shorten(suggestion, 400))
          .append("\n");
    }

    // Keep SMS under ~1500 chars
    return shorten(b.toString(), 1500);
  }

  // -----------------------------------------------------------
  // PHONE NORMALIZATION FOR ClickSend
  // -----------------------------------------------------------

  /**
   * For ClickSend, just clean the number a bit:
   *  - remove spaces and dashes
   *  - keep whatever the user configured (e.g. 0908..., +63908...)
   */
  private String normalizePhone(String raw) {
    if (raw == null) return null;
    return raw.trim()
        .replace(" ", "")
        .replace("-", "");
  }

  // -----------------------------------------------------------
  // HELPERS
  // -----------------------------------------------------------

  private String asStr(Object o) {
    return asStr(o, null);
  }

  private String asStr(Object o, String def) {
    if (o == null) return def;
    String s = String.valueOf(o).trim();
    return s.isEmpty() ? def : s;
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

  private Integer extractSignal(Object o) {
    if (o == null) return null;
    if (o instanceof Number n) return n.intValue();
    if (o instanceof String s) {
      try { return Integer.parseInt(s.trim()); } catch (Exception ignored) {}
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private String extractMovement(Map<String, Object> cycl) {
    if (cycl == null) return "";
    Object mv = cycl.get("movement");
    if (!(mv instanceof Map<?, ?> m)) return "";
    Object t = m.get("text");
    return t == null ? "" : t.toString();
  }

  private String shorten(String s, int max) {
    if (s == null) return "";
    s = s.trim();
    if (s.length() <= max) return s;
    return s.substring(0, max - 1) + "…";
  }
}