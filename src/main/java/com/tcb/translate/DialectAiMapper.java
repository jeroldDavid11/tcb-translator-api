package com.tcb.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class DialectAiMapper {

    @Value("${app.openai.apiKey:}")
    private String apiKey;

    @Value("${app.openai.model:gpt-4o-mini}")
    private String model;

    @Value("${app.openai.timeoutSeconds:15}")
    private int timeoutSec;

    private final WebClient web = WebClient.builder()
            .baseUrl("https://api.openai.com/v1/chat/completions")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();

    private final ObjectMapper om = new ObjectMapper();

    // ----------------------------------------------------
    // MAIN: MAP USER DIALECT TO NORMALIZED CODE
    // ----------------------------------------------------
    public Mono<String> mapDialect(String dialectInput) {
        if (dialectInput == null || dialectInput.isBlank()) {
            return Mono.just("english");
        }

        // If no API → fallback immediately
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.just(fallbackNormalize(dialectInput));
        }

        // AI classifier
        String sys = """
            You classify Philippine dialect names into ONE standard label. 
            Valid outputs ONLY:
            Tagalog, English, Cebuano, Ilocano, Hiligaynon, Waray-Waray,
            Kapampangan, Bikol, Pangasinan, Maranao, Maguindanaon, 
            Tausug, Chavacano, Kinaray-a.

            Respond with exact label text only.
            """;

        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", sys),
                        Map.of("role", "user", "content", dialectInput)
                ),
                "temperature", 0
        );

        return web.post()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(timeoutSec))
                .map(resp -> extract(resp, dialectInput))
                .onErrorReturn(fallbackNormalize(dialectInput));
    }

    private String extract(Map resp, String fallback) {
        try {
            List<Map> choices = (List<Map>) resp.get("choices");
            if (choices == null || choices.isEmpty()) return fallbackNormalize(fallback);

            Map msg = (Map) choices.get(0).get("message");
            if (msg == null) return fallbackNormalize(fallback);

            String out = String.valueOf(msg.get("content")).trim();
            return normalize(out);
        } catch (Exception e) {
            return fallbackNormalize(fallback);
        }
    }

    // ----------------------------------------------------
    // FALLBACK RULES (NO AI)
    // ----------------------------------------------------
    private String fallbackNormalize(String d) {
        return normalize(d);
    }

    // ----------------------------------------------------
    // NORMALIZE DIALECT NAMES
    // ----------------------------------------------------
    private String normalize(String d) {
        if (d == null) return "english";
        String s = d.trim().toLowerCase();

        if (s.contains("tag")) return "tagalog";
        if (s.contains("eng")) return "english";
        if (s.contains("ceb") || s.contains("bisaya")) return "cebuano";
        if (s.contains("iloc")) return "ilocano";
        if (s.contains("hil") || s.contains("ilonggo")) return "hiligaynon";
        if (s.contains("war")) return "waray";
        if (s.contains("kapan") || s.contains("pampanga")) return "kapampangan";
        if (s.contains("bik") || s.contains("bic")) return "bikol";
        if (s.contains("pang")) return "pangasinan";
        if (s.contains("maran")) return "maranao";
        if (s.contains("maguin")) return "maguindanaon";
        if (s.contains("taus")) return "tausug";
        if (s.contains("chav") || s.contains("zambo")) return "chavacano";
        if (s.contains("kinaray") || s.contains("karay")) return "kinaray-a";

        return "english";
    }
}