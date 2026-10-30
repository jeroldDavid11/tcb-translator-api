package com.tcb.sms.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

/**
 * Sends SMS via Semaphore (Philippines).
 *
 * Docs: https://semaphore.co/docs
 * Endpoint: POST https://api.semaphore.co/api/v4/messages
 */
@Service
public class SemaphoreSmsService {

  private static final Logger log = LoggerFactory.getLogger(SemaphoreSmsService.class);

  private final WebClient webClient;
  private final String apiKey;
  private final String senderName;
  private final ObjectMapper om = new ObjectMapper();

  public SemaphoreSmsService(
      WebClient.Builder builder,
      @Value("${app.semaphore.apiKey}") String apiKey,
      @Value("${app.semaphore.senderName:SEMAPHORE}") String senderName
  ) {
    this.webClient = builder
        .baseUrl("https://api.semaphore.co")
        .build();
    this.apiKey = apiKey;
    this.senderName = senderName;
  }

  /**
   * Sends a single SMS via Semaphore.
   *
   * @param number  E.164 or local PH format (e.g. +639xxxxxxxxx)
   * @param message Message text
   * @return message_id from Semaphore if available, null otherwise
   */
  public String sendSms(String number, String message) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("Semaphore API key (app.semaphore.apiKey) is not configured");
    }

    try {
      String raw = webClient.post()
          .uri("/api/v4/messages")
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(BodyInserters
              .fromFormData("apikey", apiKey)
              .with("number", number)
              .with("message", message)
              .with("sendername", senderName)
          )
          .retrieve()
          .bodyToMono(String.class)
          .block();

      log.info("Semaphore raw response for {} => {}", number, raw);

      if (raw == null || raw.isBlank()) {
        log.warn("Semaphore response is empty for number {}", number);
        return null;
      }

      JsonNode root = om.readTree(raw);

      // Case 1: Successful response is usually an ARRAY: [ { ... } ]
      if (root.isArray() && root.size() > 0) {
        JsonNode first = root.get(0);
        if (first.hasNonNull("message_id")) {
          String mid = first.get("message_id").asText();
          log.info("Semaphore SMS sent to {} message_id={}", number, mid);
          return mid;
        } else {
          // No message_id but array object present – just log it
          log.warn("Semaphore array response without message_id: {}", first.toString());
          return null;
        }
      }

      // Case 2: Error object (e.g. { "code": ..., "message": "..." })
      if (root.isObject()) {
        String code = Optional.ofNullable(root.get("code")).map(JsonNode::asText).orElse("unknown");
        String msg = Optional.ofNullable(root.get("message")).map(JsonNode::asText).orElse(raw);
        String errorText = "Semaphore error code=" + code + " message=" + msg;
        log.error(errorText);
        throw new IllegalStateException(errorText);
      }

      // Fallback: unexpected format
      log.warn("Semaphore unexpected response JSON: {}", raw);
      return null;

    } catch (Exception e) {
      log.error("Semaphore sendSms failed for {}: {}", number, e.toString());
      throw new RuntimeException("Semaphore sendSms failed: " + e.getMessage(), e);
    }
  }
}