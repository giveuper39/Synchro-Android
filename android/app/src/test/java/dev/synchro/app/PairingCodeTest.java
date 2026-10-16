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

  private static String link(String server, String appUri) {
    return server + "/connect#pair=" + encode(appUri);
  }

  @Test
  public void sameQrConnectsFromCameraHttpLinkAndKeepsInstalledAppUriSupport() {
    String server = "http://192.168.1.22:8765";
    PairingCode code = PairingCode.parse(link(server, code(server)));
    assertEquals(server, code.server);
    assertEquals(TOKEN, code.token);
    assertEquals("Мой компьютер", code.name);
    assertEquals(TOKEN, PairingCode.parse(link(server, code(server)) + "&install=1").token);
    assertEquals(
        TOKEN,
        PairingCode.parse(link("http://192.168.1.22", code("http://192.168.1.22:80"))).token);
  }

  @Test
  public void cameraLinksRejectCrossOriginNestedOrAmbiguousSecrets() {
    String server = "http://192.168.1.22:8765";
    String valid = link(server, code(server));
    String[] invalidLinks = {
      link("http://foreign.example", code(server)),
      link("https://192.168.1.22:8765", code(server)),
      link("http://192.168.1.22:8766", code(server)),
      valid + "&pair=" + encode(code(server)),
      valid + "&install=other",
      valid + "&redirect=https://foreign.example",
      link(server, valid),
      valid.replace("/connect#", "/arbitrary#"),
      valid.replace("/connect#", "/connect?token=leaked#"),
      code(server).replace("//pair?", "//pair:99?")
    };
    for (String value : invalidLinks) {
      try {
        PairingCode.parse(value);
        fail("Accepted invalid camera link");
      } catch (IllegalArgumentException expected) {
      }
    }
  }

  @Test
  public void compactCameraLinkDerivesTheComputerFromItsOwnOrigin() {
    String server = "http://192.168.1.22:8765";
    PairingCode code =
        PairingCode.parse(
            server + "/connect#v=1&token=" + TOKEN + "&name=" + encode("Мой компьютер"));
    assertEquals(server, code.server);
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
