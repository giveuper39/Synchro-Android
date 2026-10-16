package dev.synchro.app;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only a normal HTTP(S) URL is actionable. Shared text never becomes HTML or executable code. */
final class SharedLink {
  static String validate(String value) {
    if (value == null || value.length() > 8192 || value.matches("(?s).*[\\s\\p{Cntrl}].*"))
      throw new IllegalArgumentException("Укажите веб-ссылку");
    URI uri = URI.create(value);
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null)
      throw new IllegalArgumentException("Передаются только HTTP(S) ссылки");
    return value;
  }

  static String fromText(String text) {
    Matcher matcher = Pattern.compile("https?://[^\\s<>]+", Pattern.CASE_INSENSITIVE).matcher(text);
    if (!matcher.find()) return null;
    try {
      return validate(matcher.group());
    } catch (IllegalArgumentException error) {
      return null;
    }
  }
}
