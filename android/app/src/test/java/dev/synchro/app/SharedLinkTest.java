package dev.synchro.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class SharedLinkTest {
  @Test
  public void extractsAnOperaSharedLinkWithoutTreatingSurroundingTextAsACommand() {
    assertEquals(
        "https://example.com/page?q=1#part",
        SharedLink.fromText("Страница\nhttps://example.com/page?q=1#part"));
    assertNull(SharedLink.fromText("Обычное сообщение без ссылки"));
  }

  @Test
  public void rejectsScriptsFilesAndCredentialBearingLinks() {
    for (String value :
        new String[] {
          "javascript:alert(1)",
          "file:///private",
          "https://user:pass@example.com",
          "https://example.com/\nextra"
        }) assertThrows(IllegalArgumentException.class, () -> SharedLink.validate(value));
  }

  @Test
  public void acceptsBothNewAndLegacyPairingLinks() {
    String params =
        "://pair?v=1&server=http%3A%2F%2F192.168.1.22%3A8765&token=test_key_which_is_not_a_real_secret";
    assertEquals(
        PairingCode.parse("syncro" + params).server,
        PairingCode.parse("potplayerreceiver" + params).server);
  }
}
