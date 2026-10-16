package dev.synchro.app;

import org.json.JSONException;
import org.json.JSONObject;

/** Captured on the UI thread; safe to pass to the one network executor. */
public final class ProgressUpdate {
  public final String sessionId;
  public final long sequence;
  public final boolean paused;
  private final JSONObject body;

  public ProgressUpdate(
      String sessionId,
      long sequence,
      int index,
      long positionMs,
      boolean paused,
      float rate,
      int audioIndex,
      int subtitleIndex,
      boolean subtitleEnabled,
      long subtitleDelayMs) {
    this.sessionId = sessionId;
    this.sequence = sequence;
    this.paused = paused;
    body = new JSONObject();
    try {
      body.put("sessionId", sessionId)
          .put("sequence", sequence)
          .put("index", index)
          .put("positionMs", Math.max(0, positionMs))
          .put("paused", paused)
          .put("rate", rate)
          .put("audioIndex", audioIndex)
          .put("subtitleIndex", subtitleIndex)
          .put("subtitleEnabled", subtitleEnabled)
          .put("subtitleDelayMs", subtitleDelayMs);
    } catch (JSONException impossible) {
      throw new IllegalArgumentException(impossible);
    }
  }

  public String body() {
    return body.toString();
  }
}
