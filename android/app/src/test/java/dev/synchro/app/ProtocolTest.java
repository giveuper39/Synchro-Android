package dev.synchro.app;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Exercises wire compatibility, security boundaries, and series with reordered dubbing tracks. */
public class ProtocolTest {
  private static JSONObject response() throws Exception {
    JSONObject item =
        new JSONObject()
            .put("id", "episode-1")
            .put("title", "Серия 1")
            .put("url", "http://127.0.0.1:8765/media/dummy/episode-1")
            .put(
                "audioTracks",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("index", 0)
                            .put("language", "rus")
                            .put("title", "Studio A"))
                    .put(
                        new JSONObject()
                            .put("index", 1)
                            .put("language", "rus")
                            .put("title", "Studio B")));
    return new JSONObject()
        .put("sessionId", "test-session")
        .put("sequence", 4_000_000_000L)
        .put("index", 0)
        .put("positionMs", 5_000_000_000L)
        .put("rate", 1.25)
        .put("audioIndex", 1)
        .put("subtitleIndex", -1)
        .put("subtitleEnabled", false)
        .put("subtitleDelayMs", -700)
        .put("items", new JSONArray().put(item));
  }

  @Test
  public void protocolPreservesLongPositionsAndDoesNotSyncVolume() throws Exception {
    SessionState state = new SessionState(response());
    assertEquals(5_000_000_000L, state.positionMs);
    assertEquals(4_000_000_000L, state.sequence);
    assertEquals(-700, state.subtitleDelayMs);
    assertEquals("Studio B", state.items.get(0).audioTracks.get(1).title);
    JSONObject update =
        new JSONObject(
            new ProgressUpdate(
                    state.sessionId,
                    state.sequence + 1,
                    0,
                    state.positionMs,
                    true,
                    state.rate,
                    1,
                    -1,
                    false,
                    -700)
                .body());
    assertEquals(4_000_000_001L, update.getLong("sequence"));
    assertEquals(5_000_000_000L, update.getLong("positionMs"));
    assertTrue(update.getBoolean("paused"));
    assertFalse(update.has("volume"));
  }

  @Test
  public void rejectsUnsafeMediaAndDelayOverflow() throws Exception {
    JSONObject unsafe = response();
    unsafe.getJSONArray("items").getJSONObject(0).put("url", "file:///private/video.mkv");
    assertThrows(org.json.JSONException.class, () -> new SessionState(unsafe));
    JSONObject overflow = response().put("subtitleDelayMs", Long.MIN_VALUE);
    assertThrows(org.json.JSONException.class, () -> new SessionState(overflow));
    assertThrows(
        IllegalArgumentException.class, () -> new ApiClient("http://user:pass@127.0.0.1", "test"));
    assertThrows(
        IllegalArgumentException.class, () -> new ApiClient("http://127.0.0.1/path", "test"));
  }

  @Test
  public void bufferReuseRequiresExplicitServerConfirmation() throws Exception {
    assertFalse(new SessionState(response()).resumeInPlace);
    assertTrue(new SessionState(response().put("resumeInPlace", true)).resumeInPlace);
  }

  @Test
  public void keepsStudioWhenNextEpisodeReordersTwoRussianTracks() throws Exception {
    SessionState first = new SessionState(response());
    JSONObject nextResponse = response();
    JSONArray reordered =
        new JSONArray()
            .put(new JSONObject().put("index", 0).put("language", "rus").put("title", "Studio B"))
            .put(new JSONObject().put("index", 1).put("language", "rus").put("title", "Studio A"));
    nextResponse.getJSONArray("items").getJSONObject(0).put("audioTracks", reordered);
    SessionState next = new SessionState(nextResponse);
    assertEquals(
        0,
        PlaybackController.matchPreferredTrack(
            first.items.get(0).audioTracks, 1, next.items.get(0).audioTracks));
  }

  @Test
  public void bearerIsInHeaderAndRedirectIsNotFollowed() throws Exception {
    ServerSocket server = new ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1"));
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<Exception> serverFailure = new AtomicReference<>();
    Thread responder =
        new Thread(
            () -> {
              while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                  socket.setSoTimeout(3000);
                  BufferedReader input =
                      new BufferedReader(
                          new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                  String requestLine = input.readLine();
                  int count = requests.incrementAndGet();
                  String line;
                  while ((line = input.readLine()) != null && !line.isEmpty()) {
                    if (line.startsWith("Authorization: ")) authorization.set(line.substring(15));
                  }
                  String response =
                      count == 1
                          ? "HTTP/1.1 302 Found\r\n"
                              + "Location: /sink\r\n"
                              + "Content-Length: 0\r\n"
                              + "Connection: close\r\n\r\n"
                          : "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}";
                  socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                  socket.getOutputStream().flush();
                  if (requestLine == null) throw new IOException("Empty HTTP request");
                } catch (IOException error) {
                  if (!server.isClosed()) serverFailure.set(error);
                }
              }
            },
            "protocol-test-http");
    responder.setDaemon(true);
    responder.start();
    try {
      ApiClient client = new ApiClient("127.0.0.1:" + server.getLocalPort(), "dummy-pairing-key");
      ApiClient.ApiException error = assertThrows(ApiClient.ApiException.class, client::session);
      assertEquals(302, error.status);
      assertEquals("Bearer dummy-pairing-key", authorization.get());
      assertEquals(1, requests.get());
      assertNull(serverFailure.get());
    } finally {
      server.close();
      responder.join(3000);
    }
  }
}
