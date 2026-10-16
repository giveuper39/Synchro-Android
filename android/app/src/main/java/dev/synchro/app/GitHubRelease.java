package dev.synchro.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** A stable, tagged release with a mandatory GitHub-computed asset digest. */
final class GitHubRelease {
  final String tag, url, sha256;
  final long size;

  private GitHubRelease(String tag, String url, String sha256, long size) {
    this.tag = tag;
    this.url = url;
    this.sha256 = sha256;
    this.size = size;
  }

  static long version(String value) {
    Matcher match = Pattern.compile("^v?(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})$").matcher(value);
    if (!match.matches())
      throw new IllegalArgumentException("Нужен стабильный тег vMAJOR.MINOR.PATCH");
    return Long.parseLong(match.group(1)) * 100000000L
        + Long.parseLong(match.group(2)) * 10000L
        + Long.parseLong(match.group(3));
  }

  static GitHubRelease parse(JSONObject release, String repository) throws Exception {
    if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
      throw new IllegalArgumentException("Invalid repository");
    String tag = release.getString("tag_name");
    version(tag);
    if (release.optBoolean("draft") || release.optBoolean("prerelease"))
      throw new IllegalArgumentException("Stable release required");
    JSONArray assets = release.getJSONArray("assets");
    for (int i = 0; i < assets.length(); i++) {
      JSONObject asset = assets.getJSONObject(i);
      if (!asset.getString("name").equals("Synchro-Android.apk")
          && !asset.getString("name").equals("Syncro-Android.apk")) continue;
      String url = asset.getString("browser_download_url"), digest = asset.optString("digest");
      long size = asset.getLong("size");
      if (!url.startsWith("https://github.com/" + repository + "/releases/download/" + tag + "/")
          || !digest.matches("sha256:[a-f0-9]{64}")
          || size <= 0
          || size > 300L * 1024 * 1024) throw new IllegalArgumentException("Invalid release asset");
      return new GitHubRelease(tag, url, digest.substring(7), size);
    }
    throw new IllegalArgumentException("В релизе нет APK");
  }
}
