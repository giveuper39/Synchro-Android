package dev.synchro.app;

import static org.junit.Assert.*;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class YouTubeStateTest {
  private JSONObject state() throws JSONException {
    return new JSONObject()
        .put("videoId", "aqz-KE-bpKQ")
        .put("title", "Big Buck Bunny")
        .put("positionMs", 12345)
        .put("durationMs", 120000)
        .put("paused", false)
        .put("rate", 1.5)
        .put("ready", true)
        .put("canChangeVideo", true);
  }

  @Test
  public void acceptsEvaluateJavascriptQuotedJsonAndBuildsMonotonicProgress() throws Exception {
    YouTubeState state = YouTubeState.javascript(JSONObject.quote(state().toString()));
    assertEquals("aqz-KE-bpKQ", state.videoId);
    assertEquals(12345, state.positionMs);
    assertEquals(1.5, state.rate, .0001);
    JSONObject packet = state.paused().progress("session-one", 42);
    assertEquals(42, packet.getLong("sequence"));
    assertTrue(packet.getBoolean("paused"));
    assertFalse(packet.has("volume"));
    assertFalse(packet.has("token"));
  }

  @Test
  public void loadingAndEmbedErrorsNeverInventAPlaybackPosition() throws Exception {
    YouTubeState state = YouTubeState.javascript("{\"ready\":false,\"error\":153}");
    assertFalse(state.ready);
    assertEquals(153, state.errorCode);
    assertEquals(0, state.positionMs);
    assertTrue(state.paused);
  }

  @Test
  public void rejectsInvalidIdsNonIntegralPositionsAndBooleanNumbers() throws Exception {
    Object[][] invalid = {
      {"videoId", "https://youtube.com/"},
      {"positionMs", true},
      {"positionMs", 12.5},
      {"positionMs", -1},
      {"durationMs", 2147483648L},
      {"paused", "false"},
      {"rate", true},
      {"rate", 12},
      {"title", "bad\0title"}
    };
    for (Object[] value : invalid) {
      JSONObject packet = state().put((String) value[0], value[1]);
      assertThrows(
          value[0].toString(),
          JSONException.class,
          () -> YouTubeState.javascript(packet.toString()));
    }
    assertThrows(JSONException.class, () -> YouTubeState.javascript("null"));
    assertThrows(JSONException.class, () -> YouTubeState.javascript("[]"));
    assertThrows(
        JSONException.class, () -> YouTubeState.javascript(state().put("ready", 1).toString()));
  }

  @Test
  public void sessionValidationRejectsImpossibleDurationAndEventNumbers() throws Exception {
    assertThrows(
        JSONException.class, () -> YouTubeState.session(state().put("positionMs", 140000)));
    YouTubeState state = YouTubeState.session(state());
    assertThrows(JSONException.class, () -> state.progress("", 1));
    assertThrows(JSONException.class, () -> state.progress("session-one", 0));
    assertThrows(JSONException.class, () -> state.progress("session-one", 9007199254740992L));
  }
}
