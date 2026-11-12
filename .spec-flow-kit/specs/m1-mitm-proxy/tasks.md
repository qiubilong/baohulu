# m1-mitm-proxy 任务清单

## 概述
- 需求: baohulu M1 可运行骨架——本地 MITM 代理抓包工具，监听微信 PC 端小程序 HTTPS 流量
- 涉及工程: baohulu（单工程，从零新建）
- 模式: Plan 降级模式
- 任务数: 8

## 任务列表

### Phase 1: 工程骨架

- [x] Task 1: Maven 工程骨架与启动入口
  - 目标文件: `pom.xml`、`.gitignore`、`src/main/java/com/baohulu/BaohuluMain.java`
  - 改动要点: pom 声明 Netty 4.1.x / BouncyCastle(bcpkix-jdk18on) / Jackson / slf4j+logback，锁定 `project.build.sourceEncoding=UTF-8` 与 `release=21`，shade 插件产出 fat-jar；BaohuluMain 读取端口/缓冲容量配置并启动 Netty（空 pipeline 占位），注册优雅停机钩子。完成后 `mvn package` 可构建、`java -jar` 可启动

### Phase 2: 数据与证书基础

- [x] Task 2: 流量模型与内存环形存储
  - 目标文件: `src/main/java/com/baohulu/traffic/TrafficEntry.java`、`src/main/java/com/baohulu/traffic/TrafficStore.java`
  - 改动要点: TrafficEntry 承载请求（方法/URL/host/path/query/请求头/请求体）与响应（状态码/响应头/响应体/耗时/截断标志/二进制标记）；TrafficStore 实现容量 1000 环形缓冲、自增 id、id 索引查询、域名过滤+分页、CopyOnWriteArrayList 事件订阅（供 WS 推送）
- [x] Task 3: CA 与动态域名证书管理
  - 目标文件: `src/main/java/com/baohulu/cert/CaCertManager.java`、`src/main/java/com/baohulu/cert/DynamicCertManager.java`
  - 改动要点: CaCertManager 首次生成自签 CA 并以 PEM 持久化到 `~/.baohulu/ca/`（存在则加载）；DynamicCertManager 用 BC 按域名签发叶子证书（SAN 含域名，短有效期），LRU 缓存，提供域名→SslContext（服务端模式）

### Phase 3: 代理核心

- [x] Task 4: 前端接入与 CONNECT/MITM 挂载
  - 目标文件: `src/main/java/com/baohulu/proxy/ProxyFrontendHandler.java`、`src/main/java/com/baohulu/proxy/HttpMitmHandler.java`
  - 改动要点: 流量分流——Host 为本机端口的直连请求移交面板 pipeline；`CONNECT` 回 200 后挂 SniHandler（取 SNI，兜底用 CONNECT host）→ SslHandler（动态证书）→ HttpServerCodec+Aggregator，将解密后明文请求交给 HttpMitmHandler；普通 HTTP 代理请求（绝对 URI）同样进入 HttpMitmHandler
- [x] Task 5: 上游转发与流量捕获
  - 目标文件: `src/main/java/com/baohulu/proxy/UpstreamForwarder.java`、`src/main/java/com/baohulu/proxy/HeaderModifier.java`
  - 改动要点: JDK HttpClient 按原方法/头/body 转发（移除 Accept-Encoding 与 hop-by-hop 头），响应回写小程序侧；请求/响应完整捕获入 TrafficStore（body 1MB 截断、Content-Type 判断二进制仅记类型与大小）；HeaderModifier 为 M2 域名级注入预留的空实现拦截点（转发前调用）

### Phase 4: 面板服务

- [x] Task 6: 面板后端（静态资源 + REST + WebSocket）
  - 目标文件: `src/main/java/com/baohulu/panel/PanelRouteHandler.java`、`src/main/java/com/baohulu/panel/WsPushHandler.java`
  - 改动要点: 静态资源从 classpath `web/` 托管；`GET /api/traffic?domain=&page=` 过滤分页、`GET /api/traffic/{id}` 详情、`GET /api/cert` 下载 CA 证书；`/ws` 握手升级后订阅 TrafficStore 事件，新流量以 Jackson 序列化 JSON 推送
- [x] Task 7: 前端面板页面
  - 目标文件: `src/main/resources/web/panel.html`、`src/main/resources/web/app.js`、`src/main/resources/web/style.css`
  - 改动要点: 三段式布局（工具栏：域名过滤/暂停/清空；流量列表；详情区）；WebSocket 实时刷新列表；详情含 General/请求头/Query/请求体/响应头/响应体 Tab，JSON 响应体做树状格式化渲染（自写约百行，零依赖）

### Phase 5: 系统集成

- [x] Task 8: 系统代理管理与使用说明
  - 目标文件: `src/main/java/com/baohulu/sysproxy/WinSystemProxy.java`、`README.md`
  - 改动要点: 注册表 `HKCU\...\Internet Settings` 写入/还原系统代理（先记录原值），shutdown hook 保证退出还原，启动时检测残留并自检提示；BaohuluMain 完成全部装配；README 更新为使用说明（启动 → 安装 CA 证书 → 一键设代理 → 重启微信 → 打开面板）
