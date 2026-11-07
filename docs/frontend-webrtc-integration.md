# 大屏前端 WebRTC 视频接入指南

<!-- @author MoShangHuaKai -->

更新日期：2026-11-05。适用范围：当前展厅后端的单路摄像头实时预览，面向局域网部署。本文可直接交给前端开发人员。

## 1. 接入目标

将大屏左侧“视频流占位区域”替换为实际摄像头视频，同时保留原有边框、背景装饰和大屏布局。

播放链路：摄像头 H.264 MAIN → ZLMediaKit → 浏览器 WebRTC。Java 后端负责同源信令与会话管理，视频直接从媒体服务发送到浏览器。当前只接收视频，无音频，不申请浏览器摄像头或麦克风权限。

**推荐复用后端提供的 ExhibitionLivePlayer。** 组件已经封装获取会话、SDP 协商、视频轨道绑定、心跳、自动重连和退出释放，无需另写一套协议处理代码。

注意：playUrl 是 SDP POST 协商接口，不能设置为 video.src，也不是可直接播放的视频文件地址。

## 2. 联调前提与地址

启动顺序：MySQL → 摄像头联网 → ZLMediaKit → Java 后端 → 前端页面。

先用 Chrome 或 Edge 打开后端独立播放页，确认实体视频正常：

[http://localhost:8080/api/screen/live/player](http://localhost:8080/api/screen/live/player)

| 项目 | 当前值或约定 | 说明 |
|---|---|---|
| Java 后端 | http://localhost:8080 | localhost 仅代表浏览器或代理所在电脑自己 |
| 前端开发服务 | 示例 http://localhost:5173 | 以实际项目为准 |
| 媒体电脑局域网 IP | 上次验收为 192.168.2.100 | DHCP 变化后需要重新确认 |
| 摄像头 IP | 192.168.0.232 | 不能当作媒体电脑地址填写 |
| 媒体管理 API | TCP 9999 | 仅后端本机访问，前端不调用 |
| WebRTC 媒体 | UDP/TCP 8000 | 浏览器必须能访问媒体电脑 |

独立播放页不正常时，先解决后端、媒体服务或摄像头问题，再联调大屏。设备密码、RTSP 凭据和媒体管理密钥均保留在服务端，不放入前端代码。

## 3. 开发环境：配置同源代理

示例使用 Vite。将以下内容合并到现有 vite.config.js 或 vite.config.ts，保留原有框架插件、别名等配置：

~~~javascript
import { defineConfig } from 'vite';

export default defineConfig({
  // 保留项目原有 plugins 等配置。
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
        // 不添加 rewrite，保留 /api 前缀。
      }
    }
  }
});
~~~

修改后重新启动前端开发服务。前端与后端在不同电脑时，将 target 中的 localhost 换成后端电脑实际 IP。

必须代理完整的 /api 路径，包括 player.js、获取会话、offer、heartbeat、close。后端返回的地址是以 /api 开头的根相对地址，浏览器会请求当前前端域名；仅首次请求使用后端绝对 URL，会造成后续请求找错服务。

当前示例默认 Java 没有额外 context-path。如果后端设置了 /display 等前缀，脚本路径、endpoint 和代理路径都要包含此前缀，并完整保留后端返回的 URL。

## 4. 加载后端播放器

在前端 index.html 中，放到应用入口脚本之前：

~~~html
<!-- 普通脚本：通过前端 /api 代理加载，不使用 type="module"。 -->
<script src="/api/screen/live/player.js"></script>

<!-- 此处保留项目原来的入口，可能是 main.js 或 main.ts。 -->
<script type="module" src="/src/main.js"></script>
~~~

加载成功后，浏览器提供 window.ExhibitionLivePlayer。该脚本不需要安装第三方播放器依赖。

如果项目启用了 CSP，需要允许该同源脚本和现有页面脚本策略；不要为接入视频直接关闭整体 CSP。前端项目若采用其他构建框架，保留“普通脚本先加载，应用后初始化”的顺序即可。

## 5. Vue 3 完整示例

在实际前端工程中新增 CameraPreview.vue。仓库目前没有大屏前端工程，以下代码是接入模板，需要放入前端项目。

~~~vue
<!--
  摄像头实时视频组件，保留大屏外部容器的布局与装饰。
  @author MoShangHuaKai
-->
<template>
  <div class="camera-preview">
    <video
      ref="videoRef"
      class="camera-preview__video"
      autoplay
      muted
      playsinline
      aria-label="摄像头实时视频"
    />

    <div
      v-if="state !== 'playing'"
      class="camera-preview__placeholder"
      role="status"
      aria-live="polite"
    >
      <span>{{ message }}</span>
      <button
        v-if="state === 'error' || state === 'stopped'"
        type="button"
        @click="reconnect"
      >
        重新连接
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue';

const videoRef = ref(null);
const state = ref('connecting');
const message = ref('正在连接摄像头…');
let player = null;

onMounted(() => {
  if (!window.ExhibitionLivePlayer) {
    state.value = 'error';
    message.value = '播放器加载失败，请检查 player.js 和接口代理配置';
    return;
  }

  player = new window.ExhibitionLivePlayer(videoRef.value, {
    // 使用同源代理；不要填写摄像头或媒体管理 API 地址。
    endpoint: '/api/screen/live',
    onState(nextState, nextMessage) {
      state.value = nextState;
      message.value = nextMessage;
    }
  });
  player.start();
});

function reconnect() {
  player?.start();
}

onBeforeUnmount(() => {
  // 先关闭 PeerConnection，再通知后端释放当前观看者。
  player?.destroy();
  player = null;
});
</script>

<style scoped>
.camera-preview {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: #080c14;
}
.camera-preview__video {
  display: block;
  width: 100%;
  height: 100%;
  /* 完整显示摄像头画面，比例不同时留边。 */
  object-fit: contain;
}
.camera-preview__placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 24px;
  color: #fff;
  text-align: center;
  background: rgba(8, 12, 20, 0.7);
}
.camera-preview__placeholder button {
  padding: 8px 18px;
  color: #fff;
  cursor: pointer;
  background: transparent;
  border: 1px solid currentColor;
  border-radius: 4px;
}
</style>
~~~

在现有大屏的视频容器中使用：

~~~vue
<template>
  <!-- 保留已有大屏边框、装饰和尺寸规则。 -->
  <div class="screen-video-area">
    <CameraPreview />
  </div>
</template>

<script setup>
import CameraPreview from './components/CameraPreview.vue';
</script>
~~~

### 5.1 容器尺寸

父容器必须具有实际高度；只有 height: 100% 而所有父级都没有高度时，视频可能不可见。

- object-fit: contain：完整显示，可能留黑边，推荐。
- object-fit: cover：铺满区域，可能裁掉画面边缘和设备叠加信息。

不要保留覆盖整个视频的旧占位层，否则连接成功后也看不到画面。

### 5.2 KeepAlive 与页面切换

普通页面使用上面的卸载钩子即可。如果组件被 KeepAlive 缓存，停用不会立即卸载，需要额外处理：

1. onMounted 中只创建播放器，删除其中的 player.start()。
2. 从 vue 额外导入 onActivated、onDeactivated。
3. 加入以下钩子，onBeforeUnmount 的 destroy() 保持不变：

~~~javascript
// 首次激活和从缓存恢复时开始播放，避免与 onMounted 重复启动。
onActivated(() => player?.start());
// 切换到其他页面时停止占用视频连接；下次激活重新获取会话。
onDeactivated(() => player?.stop());
~~~

### 5.3 TypeScript 项目

当前后端脚本通过 window 暴露类，未提供 npm 包或类型声明。TypeScript 项目应按项目规范在环境声明文件中声明 Window.ExhibitionLivePlayer 的构造签名及 start/stop/destroy 方法；以上模板使用普通 JavaScript 的 script setup，不依赖类型声明。

## 6. 普通 HTML 或快速嵌入

普通 HTML 可以直接调用组件：

~~~html
<video id="camera" autoplay muted playsinline controls
       style="width:100%;height:100%;object-fit:contain"></video>
<script src="/api/screen/live/player.js"></script>
<script>
  const cameraPlayer = new window.ExhibitionLivePlayer(
    document.getElementById('camera'),
    {
      endpoint: '/api/screen/live',
      onState: (state, message) => console.log(state, message)
    }
  );
  cameraPlayer.start();
  // 单页应用移除此区域时调用 cameraPlayer.destroy()。
</script>
~~~

如只需要快速验证嵌入，可使用已有完整播放页：

~~~html
<iframe
  src="/api/screen/live/player"
  title="摄像头实时预览"
  allow="autoplay; fullscreen"
  style="display:block;width:100%;height:100%;border:0"
></iframe>
~~~

iframe 会显示内置页标题、状态及按钮；需要贴合截图中的大屏外观时，使用 Vue 组件更合适。不同源 iframe 受目标页面嵌入策略影响，建议通过同源代理接入。

## 7. 接口契约

所有路径均以没有额外 context-path 为例。无需 Authorization 或媒体管理密钥。当前播放会话用于生命周期管理，不代替业务用户鉴权；部署时沿用系统已有的访问控制方案。

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | /api/screen/live/player.js | 无请求体 | 播放组件 JavaScript |
| GET | /api/screen/live/player | 无请求体 | 完整 HTML 播放页 |
| GET | /api/screen/live | 无请求体 | JSON 观看会话 |
| POST | /api/screen/live/{session}/offer | application/sdp 原文 | application/sdp answer 原文，HTTP 200 |
| POST | /api/screen/live/{session}/heartbeat | 无需请求体 | JSON，code=200 |
| POST | /api/screen/live/{session}/close | 无需请求体，支持 sendBeacon | JSON，code=200，重复关闭幂等 |

### 7.1 创建观看会话

~~~http
GET /api/screen/live
~~~

响应示例，message 文字以实际响应为准：

~~~json
{
  "code": 200,
  "message": "success",
  "data": {
    "protocol": "webrtc",
    "session": "观看会话UUID",
    "playUrl": "/api/screen/live/观看会话UUID/offer",
    "heartbeatUrl": "/api/screen/live/观看会话UUID/heartbeat",
    "closeUrl": "/api/screen/live/观看会话UUID/close"
  }
}
~~~

每次调用都会分配独立观看会话，多人共享同一 Java 实例中的一路摄像头拉流。不要轮询此接口，也不要将同一观看会话分给多个播放器使用。

### 7.2 SDP 协商

仅在需要自行实现 WebRTC 客户端时处理本节；推荐组件已完成全部交互。

1. 创建 RTCPeerConnection，并添加 direction=recvonly 的 video transceiver。
2. 优先选择 H.264，创建 offer 并设置 localDescription。
3. 等待 ICE 候选收集完成；当前信令没有 trickle PATCH 接口。
4. 向返回的 playUrl 提交 pc.localDescription.sdp，Content-Type 为 application/sdp。
5. 使用 response.text() 获取 answer，执行 setRemoteDescription。
6. 在 ontrack 中把媒体轨道绑定到 video.srcObject。
7. 协商完成后每 15 秒 POST heartbeatUrl，退出时 POST closeUrl，并关闭 PeerConnection。

offer 要求：仅接收视频，包含 recvonly，不包含音频或数据通道，不允许 sendrecv/sendonly，长度最多 65536 个 Java 字符。完整编码字节数可能与字符数不同，不应依赖前端精确卡边界。每个会话只协商一次。

offer 成功返回 SDP 文本；错误通常返回 JSON。项目统一 Axios 封装如果自动解包 data 或强制解析 JSON，不能直接用于 SDP 成功响应。SDP 也不应写入业务日志。

### 7.3 状态与资源释放

| 组件状态 | 含义 | 建议页面展示 |
|---|---|---|
| connecting | 获取会话、协商或重连中 | 连接提示 |
| playing | PeerConnection 已连接 | 显示视频，实际画面还需解码成功 |
| waiting | 自动播放失败 | 显示可操作的播放控件或用户点击入口 |
| error | 请求或媒体连接失败，组件会重试 | 错误说明和重连按钮 |
| stopped | 主动停止 | 停止提示或关闭占位 |

上面 Vue 示例使用 muted 自动播放；若目标浏览器仍阻止播放，需要增加 controls 或可触发 video.play() 的用户点击入口。playing 状态不等同于已测量低延迟，验收要检查画面与解码帧。

组件方法：start() 重新获取会话并连接；stop() 停止且释放当前连接；destroy() 在停止基础上移除页面事件监听。不要只移除 video 标签而不销毁播放器。

默认每 15 秒心跳，60 秒未保活后会话过期；后端每 10 秒巡检。无人观看约 60–70 秒后回收共享代理。异常退出由租约及媒体服务兜底回收，页面正常退出使用 sendBeacon 立即通知后端。当前单实例最多 32 个观看会话。

| HTTP 状态 | 含义与处理 |
|---|---|
| 400 | SDP 或请求格式不正确，检查客户端实现 |
| 404 | 会话过期或已失效，重新获取会话 |
| 409 | 重复协商或尚未协商就保活，检查调用顺序 |
| 429 | 观看会话达到上限，检查播放器是否正确销毁 |
| 503 | 配置、媒体服务、摄像头或轨道不可用，显示脱敏提示并退避重试 |

## 8. 生产部署代理

以下是已有 Nginx server 块中可合并的配置片段。前端静态资源、域名、证书和其他接口按现有项目配置；Vite 开发代理不会自动带入生产环境。

~~~nginx
location /api/ {
    # 不添加结尾斜杠，保留原请求的 /api/... 路径。
    # Nginx 与 Java 不在同一主机时，改为 Java 的实际内网地址。
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_connect_timeout 5s;
    proxy_read_timeout 90s;
    proxy_cache off;
}
~~~

网络路径：

~~~text
浏览器 → 前端 /api 同源代理 → Java 8080：播放页、SDP、心跳、关闭
浏览器 → 媒体服务器 UDP/TCP 8000：WebRTC 媒体
Java → 本机媒体管理 API 9999：创建与释放原生拉流
媒体服务器 → 摄像头 554：RTSP 拉流
~~~

HTTP 反向代理不转发 UDP 媒体流。rtc.externIP 必须是浏览器可达的媒体服务器 IP；浏览器到媒体服务器的 8000 端口需可达。管理端口 9999 保持本机白名单，不开放给前端。

正式 HTTPS 页面应通过同源 HTTPS 加载脚本和信令，避免混合内容。局域网策略、企业浏览器设置及系统防火墙需要在实际大屏机器验收。当前方案面向局域网，跨公网还需另行设计地址映射和网络穿透。

## 9. 联调与验收清单

1. 独立播放页能够看到真实摄像头画面。
2. 浏览器 Network 中 player.js 返回 200 且为 JavaScript，没有被前端 history fallback 返回 HTML。
3. 获取会话返回 protocol=webrtc，offer 返回 HTTP 200 和 SDP 文本。
4. video.videoWidth、video.videoHeight 大于零；bytesReceived、framesDecoded 持续增长。
5. 页面停留超过 60 秒仍连续播放，heartbeat 请求约每 15 秒一次。
6. 同时打开两个页面，关闭一个后另一个继续播放。
7. 离开大屏页面后不再发送心跳，不残留 PeerConnection；停止全部观看约 60–70 秒后共享代理被回收。
8. 中断拉流后自动重连，旧观看会话不被复用。
9. 在实际大屏电脑确认视频端口可达、容器无裁剪错误、画面比例合适。
10. 用同步实物时钟或同画面对照测量延迟，不根据 connected 状态推断延迟。

## 10. 常见问题

| 现象 | 优先检查 |
|---|---|
| window.ExhibitionLivePlayer 不存在 | 脚本顺序、player.js 请求、CSP、是否错误使用模块导入 |
| player.js 返回 404 或 HTML | Java 是否运行、新版路由、前端 /api 代理及 history fallback |
| 获取会话成功，后续 offer/heartbeat 404 | 根相对 URL 是否被发到错误服务、是否完整保留 /api 和 context-path |
| offer 成功但页面持续连接超时 | rtc.externIP、浏览器到媒体电脑 UDP/TCP 8000、防火墙 |
| 接口返回 503 | 媒体进程、密钥文件、摄像头连通性和 H.264 视频轨道 |
| connected 但没有画面 | video 实际尺寸、父容器高度、旧占位层、自动播放策略、解码帧 |
| 多次进入页面后出现 429 | 是否重复 new 播放器、卸载和 KeepAlive 停用时是否释放 |
| 用 video.src 设置 playUrl 无法播放 | playUrl 是 SDP 协商接口，改用播放器组件 |
| 页面关闭后短暂保留摄像头拉流 | 共享流有默认空闲回收窗口，不是每个观看者退出就立即关闭摄像头拉流 |

## 11. 当前验证范围

2026-11-05 已在本机 Chrome 实际验证摄像头 H.264、2592×1944、稳定约 25 fps，双观看者独立关闭、手动重连、拉流中断自动恢复、65 秒心跳维持及停止后约 60 秒回收。Java 8 完整 verify 为 74 项全部通过。

本文 Vue 与 Nginx 示例需在实际前端和部署工程合并验证；当前仓库没有原大屏前端工程。远端实体大屏、长时间稳定性和固定毫秒级延迟不在已通过范围。设备设置回读关键帧间隔为 25，但实测仍约 100 帧，相关现场差异见 verification.md。

相关资料：[项目 README](../README.md)、[实际验证记录](verification.md)、[媒体构建信息](media-build.md)。