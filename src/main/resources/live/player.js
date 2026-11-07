/** 可复用的单路 WebRTC 播放组件；只交换信令，不接触设备或媒体管理凭据。 @author MoShangHuaKai */
(function () {
  'use strict';
  const defaultEndpoint = new URL('./', document.currentScript.src).pathname.replace(/\/$/, '');
  class ExhibitionLivePlayer {
    constructor(video, options = {}) {
      this.video = video;
      this.endpoint = options.endpoint || defaultEndpoint;
      this.onState = options.onState || (() => {});
      this.generation = 0;
      this.retryCount = 0;
      this.active = false;
      this.onPageHide = () => this.stop();
      window.addEventListener('pagehide', this.onPageHide);
    }

    async start() {
      this.stop();
      this.active = true;
      this.retryCount = 0;
      return this.connect();
    }

    async request(url, options = {}) {
      const response = await fetch(url, { cache: 'no-store', signal: this.abort.signal, ...options });
      if (!response.ok) {
        let message = '播放请求失败，请重试';
        try { message = (await response.json()).message || message; } catch (_) { /* 错误响应可能不是 JSON。 */ }
        throw new Error(message);
      }
      return response;
    }

    async connect() {
      if (!this.active) return;
      const generation = ++this.generation;
      this.abort = new AbortController();
      this.onState('connecting', '正在连接摄像头…');
      try {
        if (!window.RTCPeerConnection) throw new Error('当前浏览器不支持 WebRTC，请使用新版 Chrome 或 Edge');
        const result = await (await this.request(this.endpoint)).json();
        // 请求结束时页面可能已停止；迟到的会话也必须归还。
        if (generation !== this.generation) {
          navigator.sendBeacon(result.data.closeUrl, '');
          return;
        }
        this.session = result.data;
        const pc = this.pc = new RTCPeerConnection({ iceServers: [] });
        const receiver = pc.addTransceiver('video', { direction: 'recvonly' });
        // 明确优先协商摄像头已有的 H.264，避免为 VP8 等编码选择转码路径。
        const capabilities = window.RTCRtpReceiver && RTCRtpReceiver.getCapabilities('video');
        if (receiver.setCodecPreferences && capabilities) {
          const codecs = capabilities.codecs.filter(codec => codec.mimeType.toLowerCase() === 'video/h264');
          if (codecs.length) receiver.setCodecPreferences(codecs);
        }
        pc.ontrack = event => {
          if (generation !== this.generation) return;
          this.video.srcObject = event.streams[0] || new MediaStream([event.track]);
          this.video.play().catch(() => this.onState('waiting', '请点击视频开始播放'));
        };
        pc.onconnectionstatechange = () => {
          if (generation !== this.generation) return;
          if (pc.connectionState === 'connected') {
            clearTimeout(this.connectTimer);
            clearTimeout(this.disconnectTimer);
            this.retryCount = 0;
            this.onState('playing', '实时连接已建立');
          } else if (pc.connectionState === 'failed') this.retry('视频连接失败，正在重连…');
          else if (pc.connectionState === 'disconnected') {
            clearTimeout(this.disconnectTimer);
            this.disconnectTimer = setTimeout(() => {
              if (generation === this.generation && pc.connectionState === 'disconnected') this.retry('视频连接中断，正在重连…');
            }, 5000);
          }
        };
        await pc.setLocalDescription(await pc.createOffer());
        // 当前后端使用一次性完整 SDP，必须等 ICE 收集完毕；不依赖 trickle PATCH。
        await this.gatherIce(pc);
        if (generation !== this.generation) return;
        const answer = await (await this.request(this.session.playUrl, {
          method: 'POST', headers: { 'Content-Type': 'application/sdp' }, body: pc.localDescription.sdp
        })).text();
        if (generation !== this.generation) return;
        await pc.setRemoteDescription({ type: 'answer', sdp: answer });
        this.heartbeat = setInterval(async () => {
          if (generation !== this.generation || this.heartbeatBusy) return;
          this.heartbeatBusy = true;
          try { await this.request(this.session.heartbeatUrl, { method: 'POST' }); }
          catch (error) { if (generation === this.generation) this.retry(error.message); }
          finally { this.heartbeatBusy = false; }
        }, 15000);
        this.connectTimer = setTimeout(() => {
          if (generation === this.generation && pc.connectionState !== 'connected')
            this.retry('媒体连接超时，请检查媒体服务器地址和 UDP/TCP 端口');
        }, 15000);
      } catch (error) {
        if (generation === this.generation && this.active) this.retry(error.message);
      }
    }

    gatherIce(pc) {
      return new Promise((resolve, reject) => {
        if (pc.iceGatheringState === 'complete') { resolve(); return; }
        let timer;
        const finish = error => {
          clearTimeout(timer);
          pc.removeEventListener('icegatheringstatechange', changed);
          this.abort.signal.removeEventListener('abort', cancelled);
          error ? reject(error) : resolve();
        };
        const changed = () => { if (pc.iceGatheringState === 'complete') finish(); };
        const cancelled = () => finish(new Error('播放已停止'));
        pc.addEventListener('icegatheringstatechange', changed);
        this.abort.signal.addEventListener('abort', cancelled, { once: true });
        timer = setTimeout(() => finish(new Error('网络候选地址收集超时，请检查浏览器网络')), 10000);
      });
    }

    retry(message) {
      if (!this.active) return;
      this.release();
      this.onState('error', message);
      const delay = Math.min(1000 * 2 ** this.retryCount++, 10000);
      this.retryTimer = setTimeout(() => this.connect(), delay);
    }

    release() {
      ++this.generation;
      clearInterval(this.heartbeat);
      clearTimeout(this.retryTimer);
      clearTimeout(this.connectTimer);
      clearTimeout(this.disconnectTimer);
      this.heartbeatBusy = false;
      if (this.abort) this.abort.abort();
      if (this.pc) { this.pc.onconnectionstatechange = null; this.pc.close(); this.pc = null; }
      this.video.srcObject = null;
      if (this.session) {
        // 页面退出优先使用 beacon；后端超时回收处理窗口被强制结束的情况。
        const url = this.session.closeUrl;
        if (!navigator.sendBeacon(url, '')) fetch(url, { method: 'POST', keepalive: true }).catch(() => {});
        this.session = null;
      }
    }

    stop() {
      this.active = false;
      this.release();
      this.onState('stopped', '播放已停止');
    }

    destroy() {
      this.stop();
      window.removeEventListener('pagehide', this.onPageHide);
    }
  }
  window.ExhibitionLivePlayer = ExhibitionLivePlayer;
}());
