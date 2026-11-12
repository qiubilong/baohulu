# m1-mitm-proxy 测试用例

> Plan 降级模式自动生成（spec-test-acceptance 阶段），依据 tasks.md 8 任务 + coding-report.md 待测试验证项 + 源码分析。

## 测试概览

| 类型 | 数量 | 说明 |
|------|------|------|
| 单元测试 | 16 | JUnit 5，纯内存/临时目录，零 Mock 零外网 |
| 集成测试 | 12 | 进程内 Netty 服务 + JDK HttpServer 内嵌上游 + JDK WebSocket 客户端 |
| 手工验证 | 4 | 系统级副作用与真实环境项，不自动化 |

## 单元测试

| ID | 用例 | 类型 | 前置 | 步骤 | 预期 | 优先级 |
|----|------|------|------|------|------|--------|
| TC-U01 | TrafficStore 新增自增 id 且列表最新在前 | 正常 | store 容量 100 | 插 3 条后查列表 | id 严格递增；列表第 1 条为最后插入 | 高 |
| TC-U02 | TrafficStore 环形淘汰 | 边界 | store 容量 3 | 插 5 条 | 仅剩 id 3/4/5；getTrafficById(1)=null | 高 |
| TC-U03 | TrafficStore 容量防御 | 异常 | - | new TrafficStore(0) | 抛 IllegalArgumentException | 中 |
| TC-U04 | TrafficStore 域名过滤+分页越界 | 正常/边界 | 插 example.com 与 api.github.com 各若干 | queryTrafficList("EXAMPLE")；page=99 | 过滤忽略大小写包含匹配；越界页返回空 | 高 |
| TC-U05 | TrafficStore 监听器通知/移除/异常隔离 | 正常/异常 | 注册两个监听器其一抛异常 | addTraffic 触发 | 两个均被调用；异常监听器不影响另一个与主流程；remove 后不再通知 | 中 |
| TC-U06 | TrafficStore 清空 | 正常 | 插 3 条 | clearTraffic | 列表空、count=0、按 id 查 null | 中 |
| TC-U07 | AppConfig 默认值 | 正常 | 无相关系统属性 | loadFromSystemProperties | port=8899/capacity=1000/bodyMaxKb=1024/autoProxy=true | 高 |
| TC-U08 | AppConfig 系统属性覆盖 | 正常 | 设 4 个属性 | loadFromSystemProperties | 全部取属性值；auto.proxy=false 生效 | 高 |
| TC-U09 | CaCertManager 首次生成并持久化 | 正常 | @TempDir 空目录 | 构造两次（同目录） | 首次生成 ca-cert.pem/ca-key.pem；PEM 非空含 BEGIN CERTIFICATE | 高 |
| TC-U10 | CaCertManager 加载复用同 CA | 正常 | @TempDir 已生成 | 第二次构造后 getCaCertificatePem | 与第一次输出逐字节一致 | 高 |
| TC-U11 | DynamicCertManager 签发与缓存 | 正常 | 真实 CaCertManager | getOrCreateSslContext("a.com") 两次 + "b.com" 一次 | 返回服务端 SslContext；同域名同实例（缓存命中）；不同域名不同实例 | 高 |
| TC-U12 | DynamicCertManager IP 分支签发 | 正常 | 同上 | getOrCreateSslContext("127.0.0.1") | iPAddress SAN 分支正常签发且独立缓存；均服务端模式（SAN 内容校验受 SslContext API 限制，由 TC-I09 真实握手与 TC-M01 手工 MITM 覆盖） | 中 |
| TC-U13 | GrayHeaderModifier 默认关闭不注入 | 正常 | 默认实例 | modifyHeaders 调用 | 原有头保留，无 grayreleasetag | 高 |
| TC-U14 | GrayHeaderModifier 开启注入 | 正常 | toggle 后 | modifyHeaders | 注入 grayreleasetag=gray | 高 |
| TC-U15 | GrayHeaderModifier 已有同款头不覆盖 | 边界 | toggle 后，预置 GrayReleaseTag | modifyHeaders | 保留原值，不追加重复头 | 中 |
| TC-U16 | GrayHeaderModifier 关闭后恢复不注入 | 正常 | 开启后再 toggle | modifyHeaders | 恢复无注入 | 高 |

## 集成测试（ProxyServerIntegrationTest，进程内真实装配）

| ID | 用例 | 类型 | 前置 | 步骤 | 预期 | 优先级 |
|----|------|------|------|------|------|--------|
| TC-I01 | 服务启动与面板静态资源 | 正常 | 随机端口启动装配链 | GET /、/app.js、/style.css | 200；/ 含 "baohulu"；Content-Type 正确 | 高 |
| TC-I02 | 流量列表空态结构 | 正常 | 服务已启动 | GET /api/traffic | 200；{total:0, page:1, list:[]} | 高 |
| TC-I03 | HTTP 代理转发与捕获 | 正常 | 内嵌上游返回 200+JSON | 客户端走代理请求 http://127.0.0.1:{up}/path?q=1 | 响应体透传；/api/traffic 捕获 host=127.0.0.1、path=/path、query=q=1、status=200 | 高 |
| TC-I04 | 流量详情完整性 | 正常 | TC-I03 已捕获 | GET /api/traffic/{id} | 含 requestHeaders（Host）、responseBody、durationMs、responseContentType | 高 |
| TC-I05 | /api/clear 方法校验与清空 | 正常/异常 | 已有流量 | GET /api/clear → 405；POST → 清空 | GET 405；POST 200 后列表 total=0 | 高 |
| TC-I06 | CA 证书下载 | 正常 | 服务已启动 | GET /api/cert | 200；application/x-x509-ca-cert；含 BEGIN CERTIFICATE | 中 |
| TC-I07 | WebSocket 实时推送与心跳 | 正常 | WS 连 /ws | sendPing 等 Pong；经代理产生新请求 | onPong 收到心跳响应；onText 收到 JSON，含 id/host/method | 高 |
| TC-I08 | 域名过滤（服务端） | 正常 | 已捕获多域名 | GET /api/traffic?domain=xxx | 仅返回 host 含关键字的记录 | 中 |
| TC-I09 | CONNECT 隧道 MITM 链路 | 正常/异常 | CONNECT 指向已关闭端口 | raw CONNECT→TLS 握手（信任所有）→发 GET | 200 Connection Established；握手成功（SNI 兑底签发+服务端 TLS 挂载生效）；上游不可达回 502 | 高 |
| TC-I10 | 二进制响应捕获标记 | 边界 | 内嵌上游返回 image/png | 经代理请求后查详情 | responseBinary=true；responseSize 正确；不保存文本 | 中 |
| TC-I11 | Accept-Encoding 剥离（大小写不敏感） | 正常 | 验收检查门修复后补入 | 客户端经代理携 Accept-Encoding: gzip 请求，内嵌上游记录收到的请求头 | 上游不收到任何大小写形态的 Accept-Encoding（保证响应体明文、JSON 可格式化） | 高 |
| TC-I12 | 灰度标记注入开关端到端 | 正常 | 面板 API 可达 | GET /api/gray 默认 false；POST /api/gray/toggle 后经代理请求；再 toggle 关闭后请求 | 默认不注入；开启后上游收到 grayreleasetag=gray；关闭后恢复不注入 | 高 |

## 手工验证项（不自动化）

| ID | 用例 | 原因 |
|----|------|------|
| TC-M01 | HTTPS MITM 解密：安装 CA 后 curl -x 127.0.0.1:8899 访问 https 站点 | 需系统信任存储全局副作用 |
| TC-M02 | 系统代理自动接管/还原：启动改注册表、Ctrl+C 退出还原 | 注册表全局副作用 + 信号交互 |
| TC-M03 | 真实微信小程序流量端到端（重启微信后面板明文可见） | 真实客户端环境 |
| TC-M04 | 面板 UI 交互：过滤/暂停/清空/详情 Tab/JSON 高亮 | 无前端自动化设施（零构建原生 JS） |
| TC-M05 | 面板一键安装 CA：点「一键安装CA」→ 弹窗提示成功 → certutil 复验 Root 存储命中 | 写系统证书存储全局副作用（与 WinSystemProxy 同理），核心逻辑 certutil+指纹复验已在真实环境人工验证 |

## 跳过说明

- WinSystemProxy 单元测试：reg.exe 直接操作 HKCU 注册表（全局副作用），编码冒烟已验 enable/false 两分支，TC-M02 手工覆盖。
- HTTPS MITM 自动化：需改造 UpstreamForwarder 注入上游信任 SSLContext（生产代码可测性改造），M1 不为此改造，TC-M01/M03 手工覆盖。
- 前端自动化：无 npm 工程与测试设施，后端侧由 TC-I01/I07 覆盖。
- CI 覆盖率预检：项目无 .gitlab-ci.yml/COVERAGE_THRESHOLD，以 JaCoCo 报告展示替代。
