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
public class BulletinFinderService {

  private final WebClient web;

  @Value("${app.pagasa.base-url}")
  private String baseUrl;

  public BulletinFinderService(WebClient webClient) {
    this.web = webClient;
  }

  /** Latest bulletin URL. */
  public Mono<String> latest() {
    return listBulletinUrls().map(urls -> urls.get(urls.size() - 1));
  }

  /** Find by a fragment in the filename (e.g. "mirasol"). Returns the most recent match. */
  public Mono<String> byFragment(String fragment) {
    String needle = fragment == null ? "" : fragment.trim().toLowerCase(Locale.ROOT);
    return listBulletinUrls().map(urls -> {
      for (int i = urls.size() - 1; i >= 0; i--) {
        String url = urls.get(i);
        String file = fileName(url).toLowerCase(Locale.ROOT);
        if (file.contains(needle)) return url;
      }
      throw new NoSuchElementException("No bulletin containing: " + fragment);
    });
  }

  /** Accepts full URL, exact filename, or name fragment. */
  public Mono<String> resolvePdfParam(String pdfParam) {
    if (pdfParam == null || pdfParam.isBlank()) {
      return latest();
    }
    String s = pdfParam.trim();
    // If it's a full URL (and ends with .pdf), accept as-is.
    try {
      URI uri = URI.create(s);
      if (uri.getScheme() != null && s.toLowerCase(Locale.ROOT).endsWith(".pdf")) return Mono.just(s);
    } catch (Exception ignored) {}

    // If it looks like a filename ending with .pdf, join with base.
    if (s.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
      String url = baseUrl.endsWith("/") ? baseUrl + s : baseUrl + "/" + s;
      return Mono.just(url);
    }

    // Otherwise treat as name fragment (e.g. "mirasol")
    return byFragment(s);
  }

  private Mono<List<String>> listBulletinUrls() {
    return web.get().uri(baseUrl).accept(MediaType.TEXT_HTML).retrieve().bodyToMono(String.class)
        .map(html -> {
          Document doc = Jsoup.parse(html, baseUrl);
          List<String> out = new ArrayList<>();
          for (Element a : doc.select("a[href]")) {
            String href = a.attr("abs:href");
            if (href == null || href.isBlank()) continue;
            String f = fileName(href).toLowerCase(Locale.ROOT);
            if (f.startsWith("tcb") && f.endsWith(".pdf")) out.add(href);
          }
          if (out.isEmpty()) throw new IllegalStateException("No TCB PDFs at " + baseUrl);
          Collections.sort(out);
          return out;
        });
  }

  private String fileName(String url) {
    try {
      String p = URI.create(url).getPath();
      int i = p.lastIndexOf('/');
      return (i >= 0) ? p.substring(i + 1) : p;
    } catch (Exception e) {
      return url;
    }
  }
}
