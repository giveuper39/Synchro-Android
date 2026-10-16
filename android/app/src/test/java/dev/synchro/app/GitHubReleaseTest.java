package dev.synchro.app;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class GitHubReleaseTest {
  private JSONObject release() throws Exception {
    return new JSONObject()
        .put("tag_name", "v0.4.0")
        .put(
            "assets",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("name", "Synchro-Android.apk")
                        .put(
                            "browser_download_url",
                            "https://github.com/owner/repo/releases/download/v0.4.0/Synchro-Android.apk")
                        .put("digest", "sha256:" + "a".repeat(64))
                        .put("size", 123)));
  }

  @Test
  public void semanticVersionOrdering() {
    assertTrue(GitHubRelease.version("v0.10.0") > GitHubRelease.version("0.9.9"));
  }

  @Test
  public void stableAssetRequiresDigest() throws Exception {
    JSONObject data = release();
    assertEquals("v0.4.0", GitHubRelease.parse(data, "owner/repo").tag);
    data.getJSONArray("assets").getJSONObject(0).remove("digest");
    assertThrows(IllegalArgumentException.class, () -> GitHubRelease.parse(data, "owner/repo"));
  }

  @Test
  public void externalUrlAndPreviewRejected() throws Exception {
    JSONObject data = release().put("prerelease", true);
    assertThrows(IllegalArgumentException.class, () -> GitHubRelease.parse(data, "owner/repo"));
    JSONObject redirected = release();
    redirected
        .getJSONArray("assets")
        .getJSONObject(0)
        .put("browser_download_url", "https://other.test/app.apk");
    assertThrows(
        IllegalArgumentException.class, () -> GitHubRelease.parse(redirected, "owner/repo"));
  }
}
