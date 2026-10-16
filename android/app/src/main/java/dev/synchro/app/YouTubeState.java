package dev.synchro.app;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Validated values crossing the native/own-page boundary. No WebView or Android dependency. */
final class YouTubeState {
  final String videoId, title;
  final long positionMs, durationMs;
  final boolean paused, ready, canChangeVideo;
  final double rate;
  final int errorCode;

  private YouTubeState(
      String videoId,
      String title,
      long positionMs,
      long durationMs,
      boolean paused,
      boolean ready,
      double rate,
      int errorCode,
      boolean canChangeVideo) {
    this.videoId = videoId;
    this.title = title;
    this.positionMs = positionMs;
    this.durationMs = durationMs;
    this.paused = paused;
    this.ready = ready;
    this.rate = rate;
    this.errorCode = errorCode;
    this.canChangeVideo = canChangeVideo;
  }

  static YouTubeState session(JSONObject data) throws JSONException {
    return parse(data, true);
  }

  static YouTubeState javascript(String encoded) throws JSONException {
    if (encoded == null || encoded.length() > 16000)
      throw new JSONException("Invalid player response");
    Object value = new JSONTokener(encoded).nextValue();
    // evaluateJavascript serializes the result of JSON.stringify as a JSON string.
    if (value instanceof String) value = new JSONTokener((String) value).nextValue();
    if (!(value instanceof JSONObject)) throw new JSONException("Invalid player response");
    JSONObject object = (JSONObject) value;
    Object ready = object.opt("ready");
    if (!(ready instanceof Boolean)) throw new JSONException("Missing player readiness");
    return parse(object, (Boolean) ready);
  }

  private static YouTubeState parse(JSONObject data, boolean ready) throws JSONException {
    int error = (int) integer(data, "error", 0, 999, 0);
    if (!ready) return new YouTubeState("", "", 0, 0, true, false, 1, error, false);
    String id = data.getString("videoId");
    if (!id.matches("[A-Za-z0-9_-]{11}")) throw new JSONException("Invalid video identity");
    String title = data.optString("title", "YouTube");
    if (title.length() > 500 || title.indexOf('\0') >= 0) throw new JSONException("Invalid title");
    Object paused = data.get("paused");
    if (!(paused instanceof Boolean)) throw new JSONException("Invalid player pause state");
    Object rateValue = data.opt("rate");
    if (!(rateValue instanceof Number)) throw new JSONException("Invalid playback rate");
    double rate = ((Number) rateValue).doubleValue();
    if (!Double.isFinite(rate) || rate < .25 || rate > 4)
      throw new JSONException("Invalid playback rate");
    long position = integer(data, "positionMs", 0, Integer.MAX_VALUE, -1);
    long duration = integer(data, "durationMs", 0, Integer.MAX_VALUE, -1);
    if (duration > 0 && position > duration + 2000)
      throw new JSONException("Position exceeds duration");
    return new YouTubeState(
        id,
        title,
        position,
        duration,
        (Boolean) paused,
        true,
        rate,
        error,
        data.opt("canChangeVideo") == Boolean.TRUE);
  }

  private static long integer(
      JSONObject object, String key, long minimum, long maximum, long fallback)
      throws JSONException {
    Object value = object.opt(key);
    if (value == null && fallback >= 0) return fallback;
    if (!(value instanceof Number)) throw new JSONException("Invalid " + key);
    double number = ((Number) value).doubleValue();
    if (!Double.isFinite(number)
        || number != Math.rint(number)
        || number < minimum
        || number > maximum) throw new JSONException("Invalid " + key);
    return ((Number) value).longValue();
  }

  YouTubeState paused() {
    return new YouTubeState(
        videoId, title, positionMs, durationMs, true, ready, rate, errorCode, canChangeVideo);
  }

  JSONObject video() throws JSONException {
    return new JSONObject()
        .put("videoId", videoId)
        .put("title", title)
        .put("positionMs", positionMs)
        .put("durationMs", durationMs)
        .put("paused", paused)
        .put("rate", rate)
        .put("canChangeVideo", canChangeVideo);
  }

  JSONObject progress(String sessionId, long sequence) throws JSONException {
    if (sessionId == null || sessionId.isEmpty() || sequence < 1 || sequence > 9007199254740991L)
      throw new JSONException("Invalid playback session");
    return video().put("sessionId", sessionId).put("sequence", sequence);
  }
}
