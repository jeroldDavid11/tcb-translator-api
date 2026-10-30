package com.tcb.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

@Component
public class WhitelistPdfHostChecker {

  private final List<String> allowedHosts;

  public WhitelistPdfHostChecker(
      @Value("${app.allowed-pdf-hosts:pubfiles.pagasa.dost.gov.ph,cdn.pagasa.dost.gov.ph}") String[] allowedHosts) {
    this.allowedHosts = Arrays.asList(allowedHosts);
  }

  public void ensureAllowed(String url) {
    try {
      URI uri = URI.create(url);
      String host = uri.getHost();
      if (host == null || allowedHosts.stream().noneMatch(host::endsWith)) {
        throw new SecurityException("Blocked host: " + host);
      }
    } catch (Exception e) {
      throw new SecurityException("Invalid PDF URL: " + url, e);
    }
  }
}