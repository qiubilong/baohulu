# m1-mitm-proxy 编码报告

## 执行摘要
- 涉及工程: baohulu
- 任务总数: 8
- 已完成: 8

## 文件变更汇总
| 文件路径 | 变更类型 | 说明 |
|---------|---------|------|
| `pom.xml` | 新增 | Maven 骨架：Netty 4.1.115/BC 1.78.1/Jackson/Logback/Lombok，UTF-8 锁定，release 21，shade fat-jar |
| `.gitignore` | 新增 | 忽略 target/、.idea/、logs/ 等 |
| `src/main/resources/logback.xml` | 新增 | 控制台日志输出（UTF-8） |
| `src/main/java/com/baohulu/BaohuluMain.java` | 新增 | 启动入口：Netty 双角色服务 + 优雅停机 |
| `src/main/java/com/baohulu/config/AppConfig.java` | 新增 | 配置 record：端口/缓冲容量/body 上限，系统属性可覆盖 |
| `src/main/java/com/baohulu/server/ProxyServerInitializer.java` | 新增 | pipeline 初始化器（骨架占位，Task 4 将重写为双角色分流） |
| `src/main/java/com/baohulu/traffic/TrafficEntry.java` | 新增 | 单条流量数据模型（请求/响应全量字段） |
| `src/main/java/com/baohulu/traffic/TrafficStore.java` | 新增 | 环形缓冲存储：域名过滤/分页/id 查询/事件订阅 |
| `src/main/java/com/baohulu/cert/CaCertManager.java` | 新增 | CA 首次生成/加载复用，PEM 持久化 ~/.baohulu/ca/ |
| `src/main/java/com/baohulu/cert/DynamicCertManager.java` | 新增 | 域名动态签发叶子证书（SAN）+ SslContext LRU 缓存 |
| `src/main/java/com/baohulu/proxy/HeaderModifier.java` | 新增 | M2 域名级 Header 注入拦截点（NOOP 空实现） |
| `src/main/java/com/baohulu/proxy/UpstreamForwarder.java` | 新增 | JDK HttpClient 上游转发 + 请求/响应捕获（截断/二进制标记/keep-alive 回写） |
| `src/main/java/com/baohulu/proxy/MitmSniHandler.java` | 新增 | SNI 提取域名 → 动态证书（无 SNI 回退 CONNECT host） |
| `src/main/java/com/baohulu/proxy/HttpMitmHandler.java` | 新增 | 明文请求处理器（绝对 URI 目标解析缓存到连接属性） |
| `src/main/java/com/baohulu/proxy/ProxyFrontendHandler.java` | 新增 | 三路分流器：CONNECT(MITM)/绝对 URI(代理)/相对 URI(面板)，管线重组+消息重放 |
| `src/main/java/com/baohulu/server/ProxyServerInitializer.java` | 重写 | 初始仅挂 HttpServerCodec+分流器（Task 6 接面板后替换占位） |
| `src/main/java/com/baohulu/server/PanelPlaceholderHandler.java` | 新增 | 面板占位（Task 6 替换） |
| `src/main/java/com/baohulu/BaohuluMain.java` | 修改 | 装配 TrafficStore/Ca/DynamicCert/UpstreamForwarder |
| `src/main/java/com/baohulu/panel/PanelRouteHandler.java` | 新增 | 面板路由：静态资源 classpath 缓存 + REST API + WS 握手分流 |
| `src/main/java/com/baohulu/panel/WsPushHandler.java` | 新增 | WebSocket 实时推送：订阅 TrafficStore 事件推送 JSON 摘要 |
| `src/main/java/com/baohulu/server/PanelPlaceholderHandler.java` | 删除 | Task 6 接入真实面板后移除占位 |
| `src/main/resources/web/panel.html` | 新增 | 三段式面板布局（工具栏/流量表/详情区/状态栏） |
| `src/main/resources/web/app.js` | 新增 | WS 实时列表+域名本地过滤+详情 Tab+零依赖 JSON 高亮 |
| `src/main/resources/web/style.css` | 新增 | 暗色主题样式 |
| `src/main/java/com/baohulu/sysproxy/WinSystemProxy.java` | 新增 | 注册表系统代理接管/还原/残留自检（reg.exe + ProcessBuilder） |
| `src/main/java/com/baohulu/config/AppConfig.java` | 修改 | 增加 autoProxy 开关（-Dbaohulu.auto.proxy，默认 true） |
| `src/main/java/com/baohulu/proxy/UpstreamForwarder.java` | 修改 | 冒烟发现的修复：绝对 URI 记录时剥离为相对 path+query |
| `README.md` | 重写 | 使用说明：构建启动→装 CA→自动代理→重启微信→面板查看 |

## 任务完成状态
| 任务 | 状态 | Commit | 自查 |
|------|------|--------|------|
| Task 1: Maven 工程骨架与启动入口 | ✅ 已完成 | `b6d2cd9` | 通过（占位类 exceptionCaught 无日志属 Minor，Task 4 整体替换） |
| Task 2: 流量模型与内存环形存储 | ✅ 已完成 | `cf65744` | 通过 |
| Task 3: CA 与动态域名证书管理 | ✅ 已完成 | `87c33a3` | 通过（修复 2 个 import 编译错误：JcaPEMWriter 包路径、X509v3CertificateBuilder 缺失） |
| Task 5: 上游转发与流量捕获（组件部分，先于 Task 4 实现以满依赖） | ✅ 已完成 | `2d8c341` | 通过（同步复制字节防异步访问已释放 ByteBuf；异步异常分支完整） |
| Task 4: 前端接入与 CONNECT/MITM 挂载 | ✅ 已完成 | `0626be1` | 通过（修复 switchPipeline 签名错误、SniHandler API 依赖 javap 实测修正为 lookup(ctx,host) 返回 Future） |
| Task 6: 面板后端（静态资源 + REST + WebSocket） | ✅ 已完成 | `a889bc4` | 通过（WsPushHandler 初版 static channel 错误重写为 volatile 实例字段 handlerAdded 绑定） |
| Task 7: 前端面板页面 | ✅ 已完成 | `df5c798` | 通过（XSS 转义覆盖完整；暂停不补渲染属设计取舍；Query 并入概览 URL 展示） |
| Task 8: 系统代理管理与使用说明 | ✅ 已完成 | `cae1fb0` | 通过（运行时冒烟：接管/不接管两分支实测；退出还原 hook 路径留测试阶段） |
| 缺陷修复: 绝对 URI 记录剥离 | ✅ 已完成 | `483b21e` | 冒烟发现 path 字段含完整 URL，修复为相对 path+query 并复验 |
| 工程修补: .gitignore | ✅ 已完成 | `680123a` | shade 副产物 dependency-reduced-pom.xml 忽略 |
| 最终评审修复: /api/clear 方法校验 + 容量防御 | ✅ 已完成 | `bb77c31` | spec-plan 步骤 5 自评审 Important/Minor 各 1 项修复 |
| 检查门修复: Accept-Encoding 剥离大小写不敏感 + TC-I11 | ✅ 已完成 | `3a64253` | 验收检查门 Important #1，修复后 21/21 通过 |
| 手工验证反馈修复: CA 信任引导日志 + certificate_unknown 降噪 | ✅ 已完成 | `e0b856d` | TC-M01 实测发现：接管代理后未装 CA 阶段整机应用 HTTPS 集中失败刷屏，首次人话提示/后续降 DEBUG + 启动警告 |
| 手工验证反馈功能: 面板一键安装 CA | ✅ 已完成 | `852f5e7` | TC-M01 实测发现：证书向导「自动选择存储」误装中级导致信任不生效（中级存储两份残留为证）；新增 CaInstaller（certutil -addstore -user Root + Windows-ROOT KeyStore 指纹复验，GraalVM 实证可读）+ POST /api/cert/install + 面板按钮 + README 双方式指引；留 TC-M05 手工验证 |
| 新增功能: 灰度标记注入 grayreleasetag=gray（FR4 首落地） | ✅ 已完成 | `ec07139` | 用户新需求：GrayHeaderModifier（AtomicBoolean 开关 + equalsIgnoreCase 防重，接入 M1 预留 HeaderModifier 拦截点，BaohuluMain 装配替换 NOOP）+ GET /api/gray / POST /api/gray/toggle + 面板「灰度」按钮实时生效；单元 4 例 + 集成 tc_i12，26/26 通过 |

## 批次记录
### 批次 1（Task 1-3）：工程骨架 + 数据基础 + 证书基础
- 构建验证：`mvn package` 成功，产出 `target/baohulu.jar`（fat-jar，约 17.7MB）
- 变更请求：无
- 遗留：无

### 批次 2（Task 4-5）：代理核心
- 实现顺序说明：UpstreamForwarder（Task 5 组件）是 HttpMitmHandler（Task 4）的编译依赖，先 Task 5 后 Task 4，任务边界不变
- 构建验证：两轮 `mvn package` 均成功
- 过程修复：JcaPEMWriter 包路径、X509v3CertificateBuilder import、switchPipeline 签名、SniHandler API（Map 构造不存在→AsyncMapping+lookup(ctx,hostname)）
- 变更请求：无
- 遗留：无（面板仍为占位，批次 3 Task 6 接入）

### 批次 3（Task 6-8）：面板服务 + 系统集成
- 构建验证：三轮 `mvn clean package` 均成功（其中一次增量构建未拷贝后写的 style.css，clean 后三资源均入 fat-jar，经 `jar tf` 复验）
- 运行时冒烟：
  - 启动后注册表自动接管（ProxyEnable=0x1、ProxyServer=127.0.0.1:8899），面板 GET / 返回 200
  - 经代理请求 http://example.com/?demo=1 转发 200，/api/traffic 捕获完整（修复后 path=/、query=demo=1）
  - `-Dbaohulu.auto.proxy=false` 启动时不动注册表（已验证）
  - 冒烟后注册表手工还原为原值（ProxyEnable=0x0、ProxyServer=127.0.0.1:7890）
- 过程修复：Task 7 前端资源打包缺失（clean 解决）、PowerShell 下 -D 参数需加引号、UpstreamForwarder 绝对 URI 记录缺陷（commit 483b21e）
- 变更请求：无
- 遗留：无

## 待测试验证项（留给测试阶段）
- 启动 `java -jar target/baohulu.jar` 服务正常监听（冒烟已验，正式验收重复）
- CA 证书生成/加载复用，以及安装到系统信任存储后 HTTPS MITM 解密（需真实信任环境）
- 真实微信小程序流量：系统代理指向工具+重启微信后 HTTPS 明文实时可见
- WebSocket 实时推送（面板开箱验证）与断线重连
- Ctrl+C 正常退出时 shutdown hook 还原系统代理注册表
- TrafficStore 环形淘汰与过滤分页
- 异常退出（强杀）后重启的残留自检提示

## 最终代码评审（spec-plan 步骤 5，--self-review）
- 范围：23755c6..HEAD 全部提交，22 文件 +2905 行
- 结论：**通过（修复后）**，Critical 0 / Important 1（已修复 `bb77c31`）/ Minor 4（3 项记录不阻塞：GET 转发携带 Content-Length:0、列表 count 与 list 双快照理论不一致、单测覆盖 0 留测试阶段；capacity 防御已顺手修复）
- 重点复核项：
  - 引用计数：CONNECT 释放、switchPipeline 重放消息所有权转移、SimpleChannelInboundHandler autoRelease——无泄漏路径
  - 管线重组时序：SniHandler addFirst 在 codec 前；WS 101 写完成回调内移除 HTTP 层，事件循环顺序保证安全
  - XSS：前端 escapeHtml 覆盖 + prettyJson 先转义后高亮 + 静态资源白名单（无路径穿越）
  - 注册表操作：reg.exe 超时/失败分级日志、restore 幂等、删不存在键值容忍
  - 并发：TrafficStore 锁外回调、ConcurrentHashMap 索引、CopyOnWriteArrayList 监听器隔离

## 验收检查门（spec-verification-gate，CodeReview 子代理复验）
- 范围：23755c6..HEAD（12 提交 26 文件 +3548 行）+ 4 份文档交叉复验，实测重跑 `mvn test` 20/20 通过
- 六维度结论：文档一致性 ✅ / 代码合规 ✅ / 实现匹配度 7.5/8 / 性能基本通过 ✅ / 安全 ✅ / 测试完整性 ✅
- Critical 0 / Important 1 / Suggestion 4，整体结论：修复后通过
- Important #1（已修复 `3a64253`）：UpstreamForwarder 中 `headers.remove("accept-encoding")` 为 LinkedHashMap 大小写敏感精确匹配，Chromium 实发 `Accept-Encoding` 无法剥离 → 上游返回 gzip 压缩响应 → 面板响应体乱码、JSON 无法格式化（违反 M1 验收标准 2）；JDK HttpClient 默认不发该头放形成自动化盲区。修复：并入小写归一 removeIf；补 TC-I11（上游记录请求头断言任意大小写形态均不命中），修复后 21/21 通过
- Suggestion 4 项留 M2（不阻塞）：响应体接收无大小上限、WS 推送每连接重复序列化与列表双快照、二进制请求体未做文本类型判断、TC-I02/I08 断言强度弱化
- 文档回写：test-cases.md 集成 10→11 新增 TC-I11；本报告追加本章节
