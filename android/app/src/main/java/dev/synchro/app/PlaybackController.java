package dev.synchro.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

/** Owns LibVLC on the main thread. Network transport and Android UI are separate. */
public final class PlaybackController {
  public enum AudioOutput {
    STANDARD("android_audiotrack"),
    COMPATIBLE("opensles");

    private final String module;

    AudioOutput(String module) {
      this.module = module;
    }
  }

  public interface Listener {
    void onChanged();

    void onStatus(String message);

    void onProgressRequested(boolean reliable);
  }

  public static final class Track {
    public final int ordinal;
    public final int vlcId;
    public final String name;

    Track(int ordinal, int vlcId, String name) {
      this.ordinal = ordinal;
      this.vlcId = vlcId;
      this.name = name;
    }
  }

  private final Handler main = new Handler(Looper.getMainLooper());
  private final LibVLC libVlc;
  private final MediaPlayer player;
  private final Listener listener;
  private final SharedPreferences audioPreferences;
  private AudioOutput audioOutput;
  private AudioOutput loadedAudioOutput;
  private boolean attached;
  private boolean destroyed;
  private boolean prepared;
  private boolean endHandled;
  private boolean audioApplied;
  private boolean subtitleApplied;
  private boolean paused = true;
  private long positionMs;
  private long resumeAnchorMs;
  private long seekRequestedAt;
  private long loadedAt;
  private boolean startupReported;
  private boolean firstOutputReported;
  private int index;
  private int audioIndex = -1;
  private int subtitleIndex = -1;
  private boolean subtitleEnabled;
  private long subtitleDelayMs;
  private float rate = 1;
  private SessionState session;

  public PlaybackController(Context context, Listener listener) {
    this.listener = listener;
    audioPreferences = context.getSharedPreferences("receiver_audio", Context.MODE_PRIVATE);
    audioOutput =
        audioPreferences.getBoolean("compatible_output", false)
            ? AudioOutput.COMPATIBLE
            : AudioOutput.STANDARD;
    ArrayList<String> options = new ArrayList<>();
    options.add("--no-video-title-show");
    options.add("--network-caching=300");
    // Match VLC Android's resampler; use its best fixed-ratio quality when needed.
    // At equal input/output rates SoXR bypasses sample-rate conversion.
    options.add("--audio-resampler=soxr");
    options.add("--soxr-resampler-quality=4");
    libVlc = new LibVLC(context.getApplicationContext(), options);
    player = new MediaPlayer(libVlc);
    player.setEventListener(event -> main.post(() -> handleEvent(event)));
  }

  public void attach(VLCVideoLayout layout) {
    if (!attached && !destroyed) {
      player.attachViews(layout, null, true, false);
      attached = true;
    }
  }

  public void detach() {
    if (attached) {
      player.detachViews();
      attached = false;
    }
  }

  public boolean hasSession() {
    return session != null;
  }

  public boolean isPreparing() {
    return session != null && !prepared && !paused;
  }

  public boolean isPaused() {
    return paused;
  }

  public int index() {
    return index;
  }

  public SessionState session() {
    return session;
  }

  public long positionMs() {
    return positionMs;
  }

  public long durationMs() {
    return prepared ? Math.max(0, player.getLength()) : 0;
  }

  public float rate() {
    return rate;
  }

  public AudioOutput audioOutput() {
    return audioOutput;
  }

  /** Local output preference, independent of the PC and the selected audio track. */
  public void setAudioOutput(AudioOutput output) {
    if (audioOutput == output) return;
    refresh();
    audioOutput = output;
    audioPreferences
        .edit()
        .putBoolean("compatible_output", output == AudioOutput.COMPATIBLE)
        .apply();
    if (hasSession() && !paused) {
      load(index, positionMs, false);
    } else {
      // Keep a paused handoff silent. MainActivity captures the PC again on Play,
      // and the next load applies this preference before starting native playback.
      listener.onStatus("Вывод звука изменится при продолжении просмотра");
      listener.onChanged();
    }
  }

  public long subtitleDelayMs() {
    return subtitleDelayMs;
  }

  public boolean subtitleEnabled() {
    return subtitleEnabled;
  }

  public int audioIndex() {
    return audioIndex;
  }

  public int subtitleIndex() {
    return subtitleIndex;
  }

  public void accept(SessionState state) {
    accept(state, false);
  }

  private void accept(SessionState state, boolean startPaused) {
    session = state;
    audioIndex = state.audioIndex;
    subtitleIndex = state.subtitleIndex;
    subtitleEnabled = state.subtitleEnabled;
    subtitleDelayMs = state.subtitleDelayMs;
    rate = state.rate;
    load(state.index, state.positionMs, false, startPaused);
  }

  public boolean canResumeBuffered() {
    return hasSession() && prepared && !endHandled && loadedAudioOutput == audioOutput;
  }

  /** Reconcile the PC check without flushing a buffer that is already playing. */
  public void acceptResume(SessionState state) {
    boolean sameMedia =
        canResumeBuffered()
            && session.items.get(index).id.equals(state.items.get(state.index).id)
            && session.items.get(index).url.equals(state.items.get(state.index).url);
    if (!sameMedia) {
      android.util.Log.i("SynchroStartup", "resumeBufferReused=false");
      accept(state, paused);
      return;
    }
    // The ownership check can differ because of a PC track/delay/rate edit.
    // That does not require a new Media or hardware decoder for the same file.
    boolean seekRequired =
        !state.resumeInPlace && Math.abs(state.positionMs - resumeAnchorMs) > 250;
    if (audioIndex != state.audioIndex) audioApplied = false;
    if (subtitleEnabled != state.subtitleEnabled || subtitleIndex != state.subtitleIndex)
      subtitleApplied = false;
    boolean delayChanged = subtitleDelayMs != state.subtitleDelayMs;
    boolean rateChanged = Float.compare(rate, state.rate) != 0;
    session = state;
    index = state.index;
    audioIndex = state.audioIndex;
    subtitleIndex = state.subtitleIndex;
    subtitleEnabled = state.subtitleEnabled;
    subtitleDelayMs = state.subtitleDelayMs;
    rate = state.rate;
    applyPendingTracks();
    if (delayChanged) player.setSpuDelay(subtitleDelayMs * 1000L);
    if (rateChanged) player.setRate(rate);
    if (seekRequired) {
      positionMs = state.positionMs;
      seekRequestedAt = SystemClock.elapsedRealtime();
      player.setTime(positionMs, false);
    }
    android.util.Log.i(
        "SynchroStartup",
        "resumeBufferReused=true serverMatch=" + state.resumeInPlace + " seek=" + seekRequired);
    listener.onChanged();
  }

  private void load(int nextIndex, long startMs, boolean preserveTrackIdentity) {
    load(nextIndex, startMs, preserveTrackIdentity, false);
  }

  private void load(
      int nextIndex, long startMs, boolean preserveTrackIdentity, boolean startPaused) {
    if (preserveTrackIdentity) {
      SessionState.Item previous = session.items.get(index);
      SessionState.Item next = session.items.get(nextIndex);
      audioIndex = matchPreferredTrack(previous.audioTracks, audioIndex, next.audioTracks);
      subtitleIndex =
          matchPreferredTrack(previous.subtitleTracks, subtitleIndex, next.subtitleTracks);
    }
    index = nextIndex;
    positionMs = Math.max(0, startMs);
    prepared = false;
    endHandled = false;
    audioApplied = false;
    subtitleApplied = false;
    paused = startPaused;
    loadedAt = SystemClock.elapsedRealtime();
    startupReported = false;
    firstOutputReported = false;
    seekRequestedAt = loadedAt;
    // LibVLC applies an audio-output change when playback starts. Stop the old
    // output first so switching backends cannot leave a second stream playing.
    player.stop();
    player.setAudioOutput(audioOutput.module);
    loadedAudioOutput = audioOutput;
    SessionState.Item item = session.items.get(index);
    Media media = new Media(libVlc, Uri.parse(item.url));
    try {
      media.setHWDecoderEnabled(true, false);
      media.addOption(":network-caching=300");
      if (startPaused) media.addOption(":start-paused");
      if (positionMs > 0) media.addOption(":start-time=" + (positionMs / 1000.0));
      player.setMedia(media);
    } finally {
      media.release();
    }
    player.play();
    listener.onStatus("Открываем: " + item.title);
    listener.onChanged();
  }

  private void handleEvent(MediaPlayer.Event event) {
    if (destroyed || session == null) return;
    switch (event.type) {
      case MediaPlayer.Event.Playing:
      case MediaPlayer.Event.Paused:
        boolean firstPlaying = !prepared;
        if (firstPlaying && !startupReported) {
          startupReported = true;
          android.util.Log.i(
              "SynchroStartup", "decoderReadyMs=" + (SystemClock.elapsedRealtime() - loadedAt));
        }
        prepared = true;
        if (firstPlaying) player.setRate(rate);
        // start-time already performs the initial seek before decoding. Seeking
        // again here flushes that freshly prepared HTTP/decoder buffer.
        applyPendingTracks();
        player.setSpuDelay(subtitleDelayMs * 1000L);
        if (paused && player.isPlaying()) player.pause();
        listener.onStatus(paused ? "Пауза" : "Просмотр на телефоне");
        break;
      case MediaPlayer.Event.ESAdded:
      case MediaPlayer.Event.ESSelected:
        applyPendingTracks();
        break;
      case MediaPlayer.Event.Vout:
        if (!firstOutputReported && event.getVoutCount() > 0) {
          firstOutputReported = true;
          android.util.Log.i(
              "SynchroStartup", "firstVideoOutputMs=" + (SystemClock.elapsedRealtime() - loadedAt));
        }
        break;
      case MediaPlayer.Event.TimeChanged:
        if (prepared && SystemClock.elapsedRealtime() - seekRequestedAt >= 700) {
          positionMs = Math.max(0, event.getTimeChanged());
        }
        break;
      case MediaPlayer.Event.EndReached:
        if (!prepared || endHandled) break;
        endHandled = true;
        if (index + 1 < session.items.size()) {
          load(index + 1, 0, true);
          listener.onProgressRequested(false);
        } else {
          positionMs = Math.max(positionMs, durationMs());
          paused = true;
          listener.onStatus("Плейлист закончился — передаём позицию компьютеру…");
          listener.onProgressRequested(true);
        }
        break;
      case MediaPlayer.Event.EncounteredError:
        paused = true;
        listener.onStatus(
            "Не удалось воспроизвести видео. Проверьте сеть и формат; нажмите «Продолжить с ПК» для"
                + " повтора.");
        // A decoder/network failure is not an explicit handoff to the PC.
        break;
      default:
        break;
    }
    listener.onChanged();
  }

  /** Called every half-second from the UI; this never interprets buffering as a pause. */
  public void refresh() {
    if (!hasSession() || destroyed) return;
    if (prepared && !paused && SystemClock.elapsedRealtime() - seekRequestedAt >= 700) {
      long value = player.getTime();
      if (value >= 0) positionMs = value;
    }
    if (prepared) applyPendingTracks();
  }

  private void applyPendingTracks() {
    if (!prepared) return;
    List<Track> audio = audioTracks();
    if (!audioApplied && !audio.isEmpty()) {
      Track requested = findOrdinal(audio, audioIndex);
      if (requested != null) {
        audioApplied =
            player.getAudioTrack() == requested.vlcId || player.setAudioTrack(requested.vlcId);
      } else if (audioIndex < 0) {
        audioIndex = ordinalForId(audio, player.getAudioTrack());
        audioApplied = true;
      } else if (SystemClock.elapsedRealtime() - loadedAt > 8000) {
        listener.onStatus("Выбранная на ПК озвучка отсутствует в этой серии. Выберите её вручную.");
        audioIndex = ordinalForId(audio, player.getAudioTrack());
        audioApplied = true;
      }
    }
    List<Track> subtitles = subtitleTracks();
    if (!subtitleApplied) {
      Track requested = findOrdinal(subtitles, subtitleIndex);
      if (!subtitleEnabled) subtitleApplied = player.getSpuTrack() == -1 || player.setSpuTrack(-1);
      else if (requested != null) {
        subtitleApplied =
            player.getSpuTrack() == requested.vlcId || player.setSpuTrack(requested.vlcId);
      } else if (subtitleIndex < 0 && !subtitles.isEmpty()) {
        int selected = ordinalForId(subtitles, player.getSpuTrack());
        subtitleIndex = selected >= 0 ? selected : subtitles.get(0).ordinal;
        Track defaultTrack = findOrdinal(subtitles, subtitleIndex);
        subtitleApplied = defaultTrack != null && player.setSpuTrack(defaultTrack.vlcId);
      } else if (SystemClock.elapsedRealtime() - loadedAt > 8000) {
        listener.onStatus("Выбранные субтитры пока недоступны. Проверьте список субтитров.");
        subtitleApplied = true;
      }
      player.setSpuDelay(subtitleDelayMs * 1000L);
    }
  }

  public List<Track> audioTracks() {
    return tracks(
        player.getAudioTracks(),
        session == null ? Collections.emptyList() : session.items.get(index).audioTracks);
  }

  public List<Track> subtitleTracks() {
    return tracks(
        player.getSpuTracks(),
        session == null ? Collections.emptyList() : session.items.get(index).subtitleTracks);
  }

  private static List<Track> tracks(
      MediaPlayer.TrackDescription[] descriptions, List<SessionState.TrackMetadata> metadata) {
    if (descriptions == null) return Collections.emptyList();
    ArrayList<Track> result = new ArrayList<>();
    Set<Integer> usedOrdinals = new HashSet<>();
    for (MediaPlayer.TrackDescription description : descriptions) {
      // LibVLC's synthetic "Disable" option is not part of ffprobe's per-type ordinal order.
      if (description.id < 0) continue;
      int ordinal = -1;
      String name = description.name == null ? "Дорожка" : description.name;
      String normalizedName = normalize(name);
      for (SessionState.TrackMetadata candidate : metadata) {
        String normalizedTitle = normalize(candidate.title);
        if (normalizedTitle.length() >= 3
            && normalizedName.contains(normalizedTitle)
            && !usedOrdinals.contains(candidate.index)) {
          if (ordinal >= 0) {
            ordinal = -1;
            break;
          }
          ordinal = candidate.index;
        }
      }
      if (ordinal < 0) {
        ordinal = result.size();
        while (usedOrdinals.contains(ordinal)) ordinal++;
      }
      usedOrdinals.add(ordinal);
      result.add(new Track(ordinal, description.id, name));
    }
    return result;
  }

  private static int ordinalForId(List<Track> tracks, int vlcId) {
    for (Track track : tracks) if (track.vlcId == vlcId) return track.ordinal;
    return -1;
  }

  private static Track findOrdinal(List<Track> tracks, int ordinal) {
    for (Track track : tracks) if (track.ordinal == ordinal) return track;
    return null;
  }

  private static String normalize(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
  }

  /** Preserve a unique studio/title or language across episodes with reordered streams. */
  static int matchPreferredTrack(
      List<SessionState.TrackMetadata> previous,
      int previousIndex,
      List<SessionState.TrackMetadata> next) {
    SessionState.TrackMetadata selected = null;
    for (SessionState.TrackMetadata track : previous)
      if (track.index == previousIndex) selected = track;
    if (selected == null || next.isEmpty()) return previousIndex;
    String title = normalize(selected.title);
    String language = normalize(selected.language);
    int bestIndex = -1;
    int bestScore = 0;
    boolean ambiguous = false;
    for (SessionState.TrackMetadata candidate : next) {
      int score = 0;
      if (!title.isEmpty() && title.equals(normalize(candidate.title))) score += 10;
      if (!language.isEmpty() && language.equals(normalize(candidate.language))) score += 2;
      if (score > bestScore) {
        bestScore = score;
        bestIndex = candidate.index;
        ambiguous = false;
      } else if (score == bestScore && score > 0) ambiguous = true;
    }
    return bestScore > 0 && !ambiguous ? bestIndex : previousIndex;
  }

  public void pauseForHandoff() {
    if (!hasSession()) return;
    refresh();
    paused = true;
    if (player.isPlaying()) player.pause();
    listener.onStatus("Передаём позицию компьютеру…");
    listener.onChanged();
    listener.onProgressRequested(true);
  }

  public void resume() {
    if (!hasSession()) return;
    resumeAnchorMs = positionMs;
    paused = false;
    player.play();
    listener.onChanged();
  }

  /** Ownership conflicts stop local playback without posting another progress update. */
  public void pauseLocally() {
    refresh();
    paused = true;
    if (player.isPlaying()) player.pause();
    listener.onChanged();
  }

  public void seek(long timeMs) {
    if (!hasSession() || !prepared) return;
    positionMs = Math.max(0, timeMs);
    long length = durationMs();
    if (length > 0) positionMs = Math.min(positionMs, length);
    seekRequestedAt = SystemClock.elapsedRealtime();
    player.setTime(positionMs, false);
    listener.onChanged();
    listener.onProgressRequested(paused);
  }

  public void selectItem(int itemIndex) {
    if (!hasSession() || itemIndex < 0 || itemIndex >= session.items.size()) return;
    load(itemIndex, 0, true);
    listener.onProgressRequested(false);
  }

  public void selectAudio(Track track) {
    if (player.setAudioTrack(track.vlcId)) {
      audioIndex = track.ordinal;
      audioApplied = true;
      listener.onChanged();
      listener.onProgressRequested(paused);
    } else listener.onStatus("Не удалось переключить озвучку");
  }

  public void selectSubtitle(Track track) {
    if (player.setSpuTrack(track == null ? -1 : track.vlcId)) {
      subtitleEnabled = track != null;
      if (track != null) subtitleIndex = track.ordinal;
      subtitleApplied = true;
      player.setSpuDelay(subtitleDelayMs * 1000L);
      listener.onChanged();
      listener.onProgressRequested(paused);
    } else listener.onStatus("Не удалось переключить субтитры");
  }

  public void setSubtitleDelay(long delayMs) {
    if (!hasSession()) return;
    subtitleDelayMs = Math.max(-3_600_000L, Math.min(3_600_000L, delayMs));
    player.setSpuDelay(subtitleDelayMs * 1000L);
    listener.onChanged();
    listener.onProgressRequested(paused);
  }

  public void setRate(float nextRate) {
    rate = nextRate;
    player.setRate(rate);
    listener.onChanged();
    listener.onProgressRequested(paused);
  }

  public ProgressUpdate snapshot(long sequence) {
    return new ProgressUpdate(
        session.sessionId,
        sequence,
        index,
        positionMs,
        paused,
        rate,
        audioIndex,
        subtitleIndex,
        subtitleEnabled,
        subtitleDelayMs);
  }

  public void release() {
    destroyed = true;
    main.removeCallbacksAndMessages(null);
    player.setEventListener(null);
    player.stop();
    detach();
    player.release();
    libVlc.release();
  }
}
