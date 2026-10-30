package com.tcb.sms.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Sends SMS via ClickSend.
 *
 * Docs: https://developers.clicksend.com/docs/rest/v3/#send-sms
 */
@Service
public class ClickSendSmsService {

  private static final Logger log = LoggerFactory.getLogger(ClickSendSmsService.class);

  private final WebClient webClient;
  private final ObjectMapper om = new ObjectMapper();

  public ClickSendSmsService(
      WebClient.Builder builder,
      @Value("${app.clicksend.username}") String username,
      @Value("${app.clicksend.apiKey}") String apiKey
  ) {
    if (username == null || username.isBlank() || apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("ClickSend credentials (username/apiKey) must be configured");
    }

    String auth = Base64.getEncoder()
        .encodeToString((username + ":" + apiKey).getBytes());

    this.webClient = builder
        .baseUrl("https://rest.clicksend.com/v3")
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + auth)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
  }

  /**
   * Sends a single SMS via ClickSend.
   *
   * @param number E.164 or local format (ClickSend will try to normalize, but better to use +63…)
   * @param message SMS text
   * @return message_id from ClickSend if available, null otherwise
   */
  public String sendSms(String number, String message) {
    try {
      Map<String, Object> payload = Map.of(
          "messages", List.of(
              Map.of(
                  "source", "java",
                  "body", message,
                  "to", number
              )
          )
      );

      String raw = webClient.post()
          .uri("/sms/send")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(payload)
          .retrieve()
          .onStatus(HttpStatusCode::isError, r ->
              r.bodyToMono(String.class).defaultIfEmpty("")
                  .map(body -> new RuntimeException("ClickSend error " + r.statusCode() + ": " + body))
          )
          .bodyToMono(String.class)
          .block();

      log.info("ClickSend raw response for {} => {}", number, raw);

      if (raw == null || raw.isBlank()) {
        log.warn("ClickSend response is empty for number {}", number);
        return null;
      }

      JsonNode root = om.readTree(raw);
      JsonNode messages = root.path("data").path("messages");
      if (messages.isArray() && messages.size() > 0) {
        JsonNode first = messages.get(0);
        if (first.hasNonNull("message_id")) {
          String mid = first.get("message_id").asText();
          log.info("ClickSend SMS sent to {} message_id={}", number, mid);
          return mid;
        } else {
          log.warn("ClickSend response without message_id: {}", first.toString());
          return null;
        }
      }

      log.warn("ClickSend unexpected JSON structure: {}", raw);
      return null;

    } catch (Exception e) {
      log.error("ClickSend sendSms failed for {}: {}", number, e.toString());
      throw new RuntimeException("ClickSend sendSms failed: " + e.getMessage(), e);
    }
  }
}