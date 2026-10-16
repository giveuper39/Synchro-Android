package dev.synchro.app;

import static org.junit.Assert.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class PairingCodeTest {
  private static final String TOKEN = "demo_key_with_more_than_24_characters";

  private static String code(String server) {
    return "potplayerreceiver://pair?v=1&server="
        + encode(server)
        + "&token="
        + TOKEN
        + "&name="
        + encode("Мой компьютер");
  }

  private static String encode(String value) {
    try {
      return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  @Test
  public void readsBothCredentialsAndUnicodeName() {
    PairingCode code = PairingCode.parse(code("http://192.168.1.22:8765"));
    assertEquals("http://192.168.1.22:8765", code.server);
    assertEquals(TOKEN, code.token);
    assertEquals("Мой компьютер", code.name);
  }

  @Test
  public void rejectsUnrelatedOrAmbiguousOrHeaderInjectingCodes() {
    String[] invalid = {
      "https://example.com",
      code("file:///private"),
      code("http://user:password@192.168.1.22"),
      code("http://192.168.1.22") + "&token=second",
      code("http://192.168.1.22").replace("v=1", "v=2"),
      code("http://192.168.1.22").replace(TOKEN, "short"),
      code("http://192.168.1.22").replace(TOKEN, TOKEN + "%0d%0aInjected")
    };
    for (String value : invalid) {
      try {
        PairingCode.parse(value);
        fail("Accepted invalid QR");
      } catch (IllegalArgumentException expected) {
      }
    }
  }
}
