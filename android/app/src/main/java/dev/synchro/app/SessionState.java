package dev.synchro.app;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Validated, immutable handoff response. A media ID identifies a file, not a timestamp. */
public final class SessionState {
  public static final class Item {
    public final String id;
    public final String title;
    public final String url;
    public final List<TrackMetadata> audioTracks;
    public final List<TrackMetadata> subtitleTracks;

    private Item(JSONObject value) throws JSONException {
      id = value.getString("id");
      title = value.optString("title", id);
      url = value.getString("url");
      audioTracks = parseTracks(value.optJSONArray("audioTracks"));
      subtitleTracks = parseTracks(value.optJSONArray("subtitleTracks"));
      URI uri;
      try {
        uri = URI.create(url);
      } catch (IllegalArgumentException e) {
        throw new JSONException("Некорректный адрес видео");
      }
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null) {
        throw new JSONException("Нужен абсолютный HTTP(S) адрес видео");
      }
    }
  }

  public static final class TrackMetadata {
    public final int index;
    public final String title;
    public final String language;

    private TrackMetadata(JSONObject value, int fallbackIndex) {
      index = value.optInt("index", fallbackIndex);
      title = value.optString("title", "");
      language = value.optString("language", "");
    }
  }

  private static List<TrackMetadata> parseTracks(JSONArray array) throws JSONException {
    ArrayList<TrackMetadata> result = new ArrayList<>();
    if (array != null)
      for (int i = 0; i < array.length(); i++) {
        result.add(new TrackMetadata(array.getJSONObject(i), i));
      }
    return Collections.unmodifiableList(result);
  }

  public final String playlistWarning;
  public final String sessionId;
  public final boolean resumeInPlace;
  public final long serverPreparationMs;
  public final long sequence;
  public final int index;
  public final long positionMs;
  public final float rate;
  public final int audioIndex;
  public final int subtitleIndex;
  public final boolean subtitleEnabled;
  public final long subtitleDelayMs;
  public final List<Item> items;
  public final boolean supportsAudioTracks, supportsSubtitles, supportsRate;

  public SessionState(JSONObject value) throws JSONException {
    JSONObject capabilities = value.optJSONObject("capabilities");
    supportsAudioTracks = capabilities == null || capabilities.optBoolean("audioTracks", true);
    supportsSubtitles = capabilities == null || capabilities.optBoolean("subtitles", true);
    supportsRate = capabilities == null || capabilities.optBoolean("rate", true);
    sessionId = value.getString("sessionId");
    resumeInPlace = value.optBoolean("resumeInPlace", false);
    JSONObject startup = value.optJSONObject("startupTiming");
    serverPreparationMs = startup == null ? -1 : startup.optLong("totalMs", -1);
    if (sessionId.trim().isEmpty()) throw new JSONException("Сервер не вернул sessionId");
    sequence = Math.max(0, value.optLong("sequence", 0));
    JSONArray array = value.getJSONArray("items");
    ArrayList<Item> parsed = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) parsed.add(new Item(array.getJSONObject(i)));
    if (parsed.isEmpty()) throw new JSONException("Плейлист компьютера пуст");
    items = Collections.unmodifiableList(parsed);
    playlistWarning = value.optString("playlistWarning", "");
    index = value.getInt("index");
    if (index < 0 || index >= items.size()) throw new JSONException("Некорректный индекс серии");
    positionMs = Math.max(0, value.optLong("positionMs", 0));
    double requestedRate = value.optDouble("rate", 1);
    rate =
        (float)
            (Double.isFinite(requestedRate) && requestedRate > 0 && requestedRate <= 12
                ? requestedRate
                : 1);
    audioIndex = value.optInt("audioIndex", -1);
    subtitleIndex = value.optInt("subtitleIndex", -1);
    subtitleEnabled = value.optBoolean("subtitleEnabled", subtitleIndex >= 0);
    subtitleDelayMs = value.optLong("subtitleDelayMs", 0);
    // Bound accidental/malicious values before conversion from milliseconds to microseconds.
    if (subtitleDelayMs < -86_400_000L || subtitleDelayMs > 86_400_000L) {
      throw new JSONException("Слишком большая задержка субтитров");
    }
  }
}
