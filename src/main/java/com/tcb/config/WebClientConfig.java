package com.tcb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

  @Bean
  public WebClient webClient(
      @Value("${app.http.read-timeout-ms:20000}") int readTimeoutMs,
      @Value("${app.http.max-in-memory-size:10485760}") int maxInMemory // 10MB
  ) {
    HttpClient http = HttpClient.create()
        .responseTimeout(Duration.ofMillis(readTimeoutMs));

    ExchangeStrategies strategies = ExchangeStrategies.builder()
        .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(maxInMemory))
        .build();

    return WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(http))
        .exchangeStrategies(strategies)
        .build();
  }
}
