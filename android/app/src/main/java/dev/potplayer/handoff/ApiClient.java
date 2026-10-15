package dev.potplayer.handoff;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Blocking HTTP transport. Call only from the application's single network executor. */
public final class ApiClient {
  private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
  private final String baseUrl;
  private final String token;

  public ApiClient(String server, String token) {
    String normalized = server.trim();
    if (!normalized.contains("://")) normalized = "http://" + normalized;
    URI uri = URI.create(normalized);
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
      throw new IllegalArgumentException("Укажите адрес компьютера: IP:порт");
    }
    this.baseUrl = normalized.replaceAll("/+$", "");
    this.token = token.trim();
    if (this.token.isEmpty() || this.token.contains("\n") || this.token.contains("\r")) {
      throw new IllegalArgumentException("Введите ключ подключения");
    }
  }

  public String server() {
    return baseUrl;
  }

  public JSONObject session() throws IOException, JSONException {
    return request("GET", "/api/session", null);
  }

  public SessionState takeover() throws IOException, JSONException {
    return new SessionState(request("POST", "/api/takeover", "{}"));
  }

  public SessionState resume(String sessionId) throws IOException, JSONException {
    return new SessionState(
        request("POST", "/api/resume", new JSONObject().put("sessionId", sessionId).toString()));
  }

  public void progress(ProgressUpdate update) throws IOException, JSONException {
    request("POST", "/api/progress", update.body());
  }

  JSONObject request(String method, String path, String body) throws IOException, JSONException {
    HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
    try {
      connection.setRequestMethod(method);
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(7000);
      connection.setInstanceFollowRedirects(false); // Never forward the pairing key to a redirect.
      connection.setRequestProperty("Authorization", "Bearer " + token);
      connection.setRequestProperty("Accept", "application/json");
      if (body != null) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream stream = connection.getOutputStream()) {
          stream.write(bytes);
        }
      }
      int status = connection.getResponseCode();
      InputStream response =
          status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
      String text = readResponse(response);
      if (status < 200 || status >= 300) {
        // Error text may contain paths or URLs; avoid logging response bodies or secrets.
        String message = "HTTP " + status;
        try {
          message = new JSONObject(text).optString("error", message);
        } catch (JSONException ignored) {
        }
        throw new ApiException(status, message);
      }
      return text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
    } finally {
      connection.disconnect();
    }
  }

  private static String readResponse(InputStream response) throws IOException {
    if (response == null) return "";
    try (InputStream input = response;
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (output.size() + count > MAX_RESPONSE_BYTES)
          throw new IOException("Ответ сервера слишком большой");
        output.write(buffer, 0, count);
      }
      return output.toString(StandardCharsets.UTF_8.name());
    }
  }

  public static final class ApiException extends IOException {
    public final int status;

    ApiException(int status, String message) {
      super(message);
      this.status = status;
    }

    public boolean retryable() {
      return status == 408 || status == 429 || status >= 500;
    }
  }
}
