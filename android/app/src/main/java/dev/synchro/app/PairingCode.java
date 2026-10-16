package dev.synchro.app;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Validated, versioned QR payload. Independent of Android for decoder/unit tests. */
final class PairingCode {
  final String server, token, name;

  private PairingCode(String server, String token, String name) {
    this.server = server;
    this.token = token;
    this.name = name;
  }

  static PairingCode parse(String contents) {
    if (contents == null || contents.length() > 2048) throw invalid();
    try {
      URI uri = URI.create(contents);
      if (!("synchro".equalsIgnoreCase(uri.getScheme())
              || "potplayerreceiver".equals(uri.getScheme())
              || "syncro".equals(uri.getScheme()))
          || !"pair".equals(uri.getHost())
          || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
          || uri.getUserInfo() != null
          || uri.getFragment() != null
          || uri.getRawQuery() == null) throw invalid();
      Map<String, String> values = new HashMap<>();
      for (String field : uri.getRawQuery().split("&")) {
        String[] parts = field.split("=", 2);
        if (parts.length != 2) throw invalid();
        String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name());
        String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name());
        if (values.put(key, value) != null) throw invalid();
      }
      if (!"1".equals(values.get("v"))) throw invalid();
      String server = values.get("server"), token = values.get("token");
      if (server == null || token == null || !token.matches("[A-Za-z0-9_-]{24,256}"))
        throw invalid();
      ApiClient client = new ApiClient(server, token);
      String name = values.getOrDefault("name", "Ваш компьютер");
      if (name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) throw invalid();
      return new PairingCode(client.server(), token, name);
    } catch (Exception error) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Это не QR-код подключения Synchro");
  }
}
