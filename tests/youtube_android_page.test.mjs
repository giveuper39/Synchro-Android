import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import vm from 'node:vm';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const html = readFileSync(path.join(root, 'android/app/src/main/assets/youtube-player.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function fixture() {
  const instance = { position: 12, duration: 120, rate: 1, state: 2,
    getCurrentTime() { return this.position; }, getDuration() { return this.duration; },
    getPlaybackRate() { return this.rate; }, getPlayerState() { return this.state; },
    getAvailablePlaybackRates() { return [1, 1.5, 2]; }, setPlaybackRate(value) { this.rate = value; },
    getVideoUrl() { return 'https://www.youtube.com/watch?v=aqz-KE-bpKQ'; },
    seekTo(value) { this.position = value; }, playVideo() { this.state = 1; },
    pauseVideo() { this.state = 2; }, destroy() {} };
  let options;
  const context = { URL, location: { origin: 'http://127.0.0.1:8765' }, setTimeout() {},
    YT: { PlayerState: { PLAYING: 1, BUFFERING: 3 }, Player: function (_target, value) { options = value; return instance; } } };
  context.window = context;
  vm.runInNewContext(script, context, { timeout: 1000 });
  context.SynchroYouTube.load({ videoId: 'aqz-KE-bpKQ', title: 'Big Buck Bunny',
    positionMs: 42500, rate: 1.5, autoplay: true, canChangeVideo: false });
  context.onYouTubeIframeAPIReady();
  return { context, instance, options };
}

test('the official embed starts at the captured position with standard controls and a secret-free origin', () => {
  const { context, instance, options } = fixture();
  assert.equal(options.playerVars.controls, 1);
  assert.equal(options.playerVars.fs, 1);
  assert.equal(options.playerVars.origin, 'http://127.0.0.1:8765');
  options.events.onReady();
  assert.equal(instance.position, 42.5);
  assert.equal(instance.rate, 1.5);
  assert.equal(instance.state, 1);
  const snapshot = context.SynchroYouTube.snapshot();
  assert.equal(snapshot.positionMs, 42500);
  assert.equal(snapshot.paused, false);
  assert.equal(snapshot.canChangeVideo, false);
  assert.equal(JSON.stringify(options).includes('token'), false);
});

test('pause snapshots preserve the position and embed errors are exposed to native UI', () => {
  const { context, instance, options } = fixture();
  options.events.onReady();
  instance.position = 61.125;
  const snapshot = context.SynchroYouTube.pause();
  assert.equal(snapshot.paused, true);
  assert.equal(snapshot.positionMs, 61125);
  options.events.onError({ data: 153 });
  assert.equal(context.SynchroYouTube.snapshot().error, 153);
});

test('network failures before IFrame readiness are explicit and no JavaScript native interface exists', () => {
  const { context } = fixture();
  context.SynchroYouTube.networkError();
  const snapshot = context.SynchroYouTube.snapshot();
  assert.equal(snapshot.ready, false);
  assert.equal(snapshot.error, 900);
  assert.equal(html.includes('addJavascriptInterface'), false);
});
