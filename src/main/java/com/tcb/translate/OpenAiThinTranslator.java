package com.tcb.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

@Service
public class OpenAiThinTranslator {

  private static final Logger log = LoggerFactory.getLogger(OpenAiThinTranslator.class);

  private final WebClient web;
  private final ObjectMapper om = new ObjectMapper();

  @Value("${app.openai.apiKey:}")
  private String apiKey;

  @Value("${app.openai.model:gpt-4o-mini}")
  private String model;

  @Value("${app.openai.timeoutSeconds:25}")
  private int timeoutSec;

  public OpenAiThinTranslator(WebClient.Builder builder) {
    this.web = builder
        .baseUrl("https://api.openai.com/v1/chat/completions")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
  }

  /**
   * Translate only "cyclone" and "tcws". If API missing/429/other error, returns original inputs.
   * Output shape:
   * {
   *   "cyclone": { "name": "...", "classification": "...", "location": {"text":"..."}, "movement":{"text":"..."} },
   *   "tcws": "..."
   * }
   */
  public Map<String, Object> translateCycloneAndTcws(Map<String, Object> cyclone,
                                                     String tcws,
                                                     String lang) {
    // If no key configured, just return originals.
    if (apiKey == null || apiKey.isBlank()) {
      Map<String, Object> out = new HashMap<>();
      out.put("_generator", "rule-original");
      out.put("cyclone", cyclone);
      out.put("tcws", tcws);
      return out;
    }

    String system =  """
    	      You are a careful translator for Philippine weather bulletins.
    	      Your job is to translate cyclone.name, cyclone.classification, 
    	      cyclone.location.text, cyclone.movement.text, and tcws into the target dialect.

    	      IMPORTANT RULES:
    	      • ALWAYS translate movement.text even if it looks Filipino already.
    	      • NEVER keep Tagalog/Filipino phrases; rewrite them completely into the target language.
    	      • Keep translations smooth, natural, and correct for the dialect.
    	      • Preserve geographic names (places, provinces, regions).
    	      • Return STRICT JSON with structure:
    	        { "cyclone": { ... }, "tcws": "..." }
    	      • No explanations, no markdown, no notes — ONLY JSON output.
    	      """;

    // Cap long TCWS just in case
    String tcwsCapped = cap(tcws, 1800);

    Map<String, Object> userObj = Map.of(
        "target_language", lang,
        "cyclone", cyclone == null ? Map.of() : cyclone,
        "tcws", tcwsCapped == null ? "" : tcwsCapped
    );

    Map<String, Object> payload = Map.of(
        "model", model,
        "temperature", 0.2,
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

      if (resp == null) return original(cyclone, tcws, "rule-original(null)");

      List<Map> choices = (List<Map>) resp.get("choices");
      if (choices == null || choices.isEmpty()) return original(cyclone, tcws, "rule-original(empty)");

      Map msg = (Map) choices.get(0).get("message");
      if (msg == null) return original(cyclone, tcws, "rule-original(nomsg)");

      String content = String.valueOf(msg.get("content"));
      if (content == null || content.isBlank()) return original(cyclone, tcws, "rule-original(blank)");

      Map<String, Object> parsed = om.readValue(content, Map.class);

      Map<String, Object> out = new HashMap<>();
      out.put("_generator", "ai");
      out.put("cyclone", parsed.getOrDefault("cyclone", cyclone));
      out.put("tcws", parsed.getOrDefault("tcws", tcws));
      return out;
    } catch (Exception e) {
      log.warn("OpenAI translateCycloneAndTcws failed: {}", e.toString());
      return original(cyclone, tcws, "rule-original(error)");
    }
  }

  private Map<String, Object> original(Map<String, Object> cyclone, String tcws, String gen) {
    Map<String, Object> out = new HashMap<>();
    out.put("_generator", gen);
    out.put("cyclone", cyclone);
    out.put("tcws", tcws);
    return out;
  }

  private String cap(String s, int max) {
    if (s == null) return "";
    String t = s.trim();
    if (t.length() <= max) return t;
    return t.substring(0, Math.max(0, max - 1)) + "…";
  }

  private String toJson(Object o) {
    try { return om.writeValueAsString(o); } catch (Exception e) { return "{}"; }
  }
}
