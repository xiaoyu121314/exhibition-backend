/** 大屏接入时序验收：不重叠、断网清空、换人及卸载。 @author MoShangHuaKai */
import test from 'node:test';
import assert from 'node:assert/strict';
import { startMonitor } from '../../../docs/examples/monitor-client.mjs';

const flush = async () => { for (let i = 0; i < 15; i++) await Promise.resolve(); };
function fakeClock() {
  let now = 0, sequence = 0;
  const jobs = new Map();
  return {
    now: () => now,
    setTimeout(fn, delay) { const id = ++sequence; jobs.set(id, { fn, at: now + delay }); return id; },
    clearTimeout(id) { jobs.delete(id); },
    async advance(ms) {
      const end = now + ms;
      for (;;) {
        const next = [...jobs].filter(([, job]) => job.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
        if (!next) break;
        now = next[1].at; jobs.delete(next[0]); next[1].fn(); await flush();
      }
      now = end; await flush();
    },
  };
}
const base = Date.parse('2026-11-05T16:00:00+08:00');
const time = ms => new Date(base + ms).toISOString();
const card = (id, eventTime) => ({ captureId: id, eventTime: time(eventTime), displayUntil: time(eventTime + 10000),
  imageId: 'capture', recognizeImageId: null, staff: { id: id, avatarId: 'avatar' } });
const ok = data => ({ ok: true, json: async () => ({ code: 200, data }) });

test('重复响应不延时，后续断网仍在10秒清空，null人数保留', async () => {
  const clock = fakeClock(), cards = [], counts = [];
  const stop = startMonitor({ clock, onStaff: value => cards.push(value), onOverview: value => counts.push(value.guestCount),
    fetchImpl: async path => {
      if (path.endsWith('/config')) return ok({});
      if (clock.now() >= 4000) throw new Error('断网');
      return ok({ generatedAt: time(clock.now()), latestStaff: card('A', 0), guestCount: null });
    } });
  await flush(); assert.equal(cards.at(-1).referenceLabel, '登记照');
  await clock.advance(9999); assert.equal(cards.at(-1).captureId, 'A');
  await clock.advance(1); assert.equal(cards.at(-1), null);
  assert.deepEqual(counts, [null, null]); stop();
});

test('新人员立即替换，迟到旧事件不覆盖，同人新事件更新', async () => {
  const clock = fakeClock(), cards = [];
  const stop = startMonitor({ clock, onStaff: value => cards.push(value), fetchImpl: async path => {
    if (path.endsWith('/config')) return ok({});
    const current = clock.now() < 2000 ? card('A', 0) : clock.now() < 4000 ? card('B', 2000)
      : clock.now() < 6000 ? card('OLD', 1000) : { ...card('B2', 6000), staff: { id: 'B' } };
    return ok({ generatedAt: time(clock.now()), latestStaff: current });
  } });
  await flush(); await clock.advance(2000); assert.equal(cards.at(-1).captureId, 'B');
  await clock.advance(2000); assert.equal(cards.at(-1).captureId, 'B');
  await clock.advance(2000); assert.equal(cards.at(-1).captureId, 'B2'); stop();
});

test('慢请求完成后才间隔2秒，卸载取消在途请求且不再轮询', async () => {
  const clock = fakeClock(); let requests = 0, aborted = 0, finish;
  const stop = startMonitor({ clock, fetchImpl: (path, { signal }) => {
    if (path.endsWith('/config')) return Promise.resolve(ok({}));
    requests++;
    return new Promise((resolve, reject) => {
      finish = () => resolve(ok({ generatedAt: time(clock.now()), latestStaff: null }));
      signal.addEventListener('abort', () => { aborted++; reject(new Error('aborted')); }, { once: true });
    });
  } });
  await flush(); await clock.advance(3000); assert.equal(requests, 1);
  finish(); await flush(); await clock.advance(1999); assert.equal(requests, 1);
  await clock.advance(1); assert.equal(requests, 2);
  stop(); await flush(); assert.equal(aborted, 1);
  await clock.advance(60000); assert.equal(requests, 2);
});
