package com.tcb.pagasa;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.*;

@Service
public class BulletinLocatorService {

  private final WebClient web;

  @Value("${app.pagasa.base-url}")
  private String baseUrl;

  public BulletinLocatorService(WebClient.Builder builder) {
    this.web = builder.build();
  }

  public Mono<String> latestPdfUrl() {
    return listBulletinUrls().map(urls -> urls.get(urls.size() - 1));
  }

  public Mono<String> resolveByName(String fragment) {
    if (fragment == null || fragment.isBlank()) {
      return latestPdfUrl();
    }
    String needle = fragment.toLowerCase(Locale.ROOT);
    return listBulletinUrls().map(urls -> {
      for (int i = urls.size() - 1; i >= 0; i--) {
        String file = fileName(urls.get(i)).toLowerCase(Locale.ROOT);
        if (file.contains(needle)) return urls.get(i);
      }
      throw new NoSuchElementException("No PDF found containing: " + fragment);
    });
  }

  private Mono<List<String>> listBulletinUrls() {
    return web.get()
        .uri(baseUrl)
        .accept(MediaType.TEXT_HTML)
        .retrieve()
        .bodyToMono(String.class)
        .map(html -> {
          Document doc = Jsoup.parse(html, baseUrl);
          List<String> urls = new ArrayList<>();
          for (Element a : doc.select("a[href]")) {
            String href = a.attr("abs:href");
            if (href.toLowerCase().endsWith(".pdf") && href.contains("TCB")) {
              urls.add(href);
            }
          }
          Collections.sort(urls);
          return urls;
        });
  }

  private String fileName(String url) {
    String path = URI.create(url).getPath();
    return path.substring(path.lastIndexOf('/') + 1);
  }
}
