/**
 * 大屏无框架接入示例。进入时立即取配置及概览，返回页面卸载时调用的stop。
 * clock/fetchImpl支持验证断网及请求时序，页面通常只传四个回调。
 * @author MoShangHuaKai
 */
export function startMonitor({
  baseUrl = '',
  onConfig = () => {},
  onOverview = () => {},
  onStaff = () => {},
  onError = () => {},
  fetchImpl = globalThis.fetch.bind(globalThis),
  clock = {
    now: () => globalThis.performance.now(),
    setTimeout: (fn, delay) => globalThis.setTimeout(fn, delay),
    clearTimeout: id => globalThis.clearTimeout(id),
  },
} = {}) {
  let stopped = false;
  let configTimer, overviewTimer, cardTimer;
  let activeCard = null, lastCaptureId = null, latestEventTime = -Infinity;
  const pending = new Set();
  const origin = baseUrl.replace(/\/$/, '');
  const mediaUrl = id => id ? origin + '/api/media/' + encodeURIComponent(id) : null;

  function clearCard() {
    clock.clearTimeout(cardTimer); cardTimer = undefined;
    if (activeCard !== null) { activeCard = null; onStaff(null); }
  }

  function updateCard(data, elapsed) {
    const card = data.latestStaff;
    if (!card) { clearCard(); return; }
    // 用两项服务端时间之差，不依赖浏览器时区或系统时钟偏差。
    // 扣除本次请求耗时，保守处理网络传输期间已经消耗的展示时间。
    const eventTime = Date.parse(card.eventTime);
    const remaining = Date.parse(card.displayUntil) - Date.parse(data.generatedAt) - elapsed;
    if (!Number.isFinite(eventTime) || !Number.isFinite(remaining)) { clearCard(); return; }
    if (eventTime < latestEventTime) return;
    // 同一captureId重复轮询不重新计时；本地已清空也不能把同一事件重新弹出。
    if (card.captureId === lastCaptureId) return;
    latestEventTime = eventTime; lastCaptureId = card.captureId;
    if (remaining <= 0) { clearCard(); return; }
    clock.clearTimeout(cardTimer);
    const referenceId = card.recognizeImageId || card.staff?.avatarId;
    activeCard = { ...card, captureUrl: mediaUrl(card.imageId), referenceUrl: mediaUrl(referenceId),
      referenceLabel: card.recognizeImageId ? '设备匹配库图' : '登记照',
      registeredAvatarUrl: mediaUrl(card.staff?.avatarId) };
    onStaff(activeCard);
    // 独立于后续HTTP请求，断网及慢请求都不能延长旧卡片寿命。
    cardTimer = clock.setTimeout(clearCard, remaining);
  }

  async function request(path, consume) {
    const controller = new AbortController(); pending.add(controller);
    const timeout = clock.setTimeout(() => controller.abort(), 5000);
    const started = clock.now();
    try {
      const response = await fetchImpl(origin + path, { signal: controller.signal, cache: 'no-store' });
      if (!response.ok) throw new Error('HTTP ' + response.status);
      const body = await response.json();
      if (body.code !== 200) throw new Error(body.message || '接口请求失败');
      if (!stopped) consume(body.data, Math.max(0, clock.now() - started));
    } catch (error) {
      if (!stopped) onError({ path, error });
    } finally {
      clock.clearTimeout(timeout); pending.delete(controller);
    }
  }
  async function refreshConfig() {
    try { await request('/api/screen/config', onConfig); }
    finally { if (!stopped) configTimer = clock.setTimeout(refreshConfig, 30000); }
  }
  async function refreshOverview() {
    try {
      await request('/api/screen/overview', (data, elapsed) => {
        // null直接传递，UI使用value == null ? '暂无统计' : String(value)。
        onOverview(data); updateCard(data, elapsed);
      });
    } finally { if (!stopped) overviewTimer = clock.setTimeout(refreshOverview, 2000); }
  }

  onStaff(null);
  void refreshConfig(); void refreshOverview();
  return function stop() {
    if (stopped) return;
    stopped = true;
    clock.clearTimeout(configTimer); clock.clearTimeout(overviewTimer);
    for (const controller of pending) controller.abort();
    clearCard();
  };
}
