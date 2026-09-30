import test from "node:test";
import assert from "node:assert/strict";
import { Replay, replayDelays } from "../../main/resources/static/demo-client.js";

function player() {
  const pending = new Map(), shown = [];
  let id = 0;
  const replay = new Replay(e => shown.push(e), () => {}, (fn, ms) => {
    pending.set(++id, { fn, ms });
    return id;
  }, key => pending.delete(key));
  const next = () => pending.values().next().value;
  const tick = () => {
    const [key, { fn }] = pending.entries().next().value;
    pending.delete(key);
    fn();
  };
  return { replay, pending, shown, next, tick };
}

test("speed set before playback applies to every row of short and long recordings", () => {
  assert.ok(replayDelays.fast < replayDelays.normal && replayDelays.normal < replayDelays.slow);
  for (const length of [5, 102]) for (const [speed, delay] of Object.entries(replayDelays)) {
    const { replay, pending, shown, next, tick } = player();
    const entries = Array.from({ length }, (_, i) => i);
    replay.setSpeed(speed);
    replay.load(entries);
    replay.play();
    for (const entry of entries) {
      assert.equal(pending.size, 1);
      assert.equal(next().ms, delay);
      tick();
      assert.equal(shown.at(-1), entry);
    }
    assert.equal(replay.state, "complete");
    assert.equal(pending.size, 0);
    assert.deepEqual(shown, entries);
  }
});

test("changing speed replaces the pending timer immediately and ignores stale callbacks", () => {
  const { replay, pending, shown, next, tick } = player();
  replay.load([1, 2, 3]);
  replay.setSpeed("slow");
  replay.play();
  const slow = next().fn;
  replay.setSpeed("fast");
  assert.equal(pending.size, 1);
  assert.equal(next().ms, 12);
  slow();
  assert.deepEqual(shown, []);
  tick();
  assert.deepEqual(shown, [1]);
  const fast = next().fn;
  replay.setSpeed("normal");
  assert.equal(next().ms, 40);
  fast();
  assert.deepEqual(shown, [1]);
  tick();
  replay.setSpeed("slow");
  assert.equal(next().ms, 200);
  tick();
  assert.deepEqual(shown, [1, 2, 3]);
  assert.equal(pending.size, 0);
});

test("changing speed while paused does not resume and is used on resume and reload", () => {
  const { replay, pending, shown, next, tick } = player();
  replay.load([1, 2]);
  replay.play();
  tick();
  replay.pause();
  replay.setSpeed("fast");
  assert.equal(replay.state, "paused");
  assert.equal(pending.size, 0);
  assert.deepEqual(shown, [1]);
  replay.play();
  assert.equal(next().ms, 12);
  tick();
  replay.setSpeed("slow");
  assert.equal(replay.state, "complete");
  assert.equal(pending.size, 0);
  replay.load([3]);
  replay.play();
  assert.equal(next().ms, 200);
  tick();
  assert.deepEqual(shown, [1, 2, 3]);
});

test("rapid speed changes, repeated play and show-all never create duplicate timers or rows", () => {
  const { replay, pending, shown, next, tick } = player();
  replay.load([1, 2, 3]);
  replay.play();
  const stale = [];
  for (const speed of ["slow", "fast", "normal", "slow", "fast"]) {
    stale.push(next().fn);
    replay.setSpeed(speed);
    replay.play();
    assert.equal(pending.size, 1);
  }
  stale.forEach(fn => fn());
  assert.deepEqual(shown, []);
  tick();
  stale.push(next().fn);
  replay.finish();
  stale.forEach(fn => fn());
  assert.deepEqual(shown, [1, 2, 3]);
  assert.equal(pending.size, 0);
  replay.finish();
  assert.deepEqual(shown, [1, 2, 3]);
});

test("invalid or unchanged speed does not disturb a pending timer", () => {
  const { replay, next, pending } = player();
  replay.load([1]);
  replay.play();
  const timer = next();
  for (const speed of ["unknown", "toString", "__proto__", null, -1]) {
    assert.throws(() => replay.setSpeed(speed), RangeError);
    assert.equal(next(), timer);
  }
  replay.setSpeed("normal");
  assert.equal(next(), timer);
  assert.equal(pending.size, 1);
});
