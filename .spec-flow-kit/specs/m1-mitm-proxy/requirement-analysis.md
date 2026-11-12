# 需求分析：baohulu M1 —— 微信小程序 MITM 抓包工具骨架

> 来源：本会话调研对话（用户已逐步确认） | 状态：工程评估后回写留档

## 1. 需求背景与目标

做一个类 Reqable（茶壶）的轻量本地抓包工具 **baohulu**，监听**微信 PC 端小程序**的 HTTP/HTTPS 流量，支持域名过滤、报文查看（JSON 格式化）、域名级请求头注入（M2）。

**环境验证（M0 已通过）**：用户已用 Reqable 实测——微信小程序流量走 Windows 系统代理、CA 根证书装入系统后可解密 HTTPS、无 SSL Pinning。自研工具与 Reqable 原理一致（系统代理 + MITM 动态证书），技术可行性已 100% 确认。

## 2. 功能需求与分期

| 编号 | 功能 | 分期 |
|---|---|---|
| FR1 | 监听 web 请求，HTTPS 解密（MITM） | M1 |
| FR2 | 按域名过滤流量 | M1 |
| FR3 | 查看请求地址/请求头/请求参数/响应头/响应体（JSON 格式化） | M1 |
| FR4 | 指定域名请求追加 Header | M2（M1 仅架构预留拦截点） |
| FR5 | 形态：本地服务 + 浏览器 HTML 面板 | M1 |

## 3. 技术方案结论（调研漏斗）

1. 纯 HTML：不可行（浏览器沙箱，无法监听其他进程流量）
2. Chrome 插件：不可行（微信小程序运行于 WeChatAppEx.exe 自带 Chromium 容器，无法装扩展；目标流量为微信小程序）
3. **本地 MITM 代理 + 浏览器面板：定案**

- 技术栈：Java 21（GraalVM 21.0.12）+ Netty + BouncyCastle + Maven；开发期 JVM 直跑，**暂不做 native-image 打包**
- 架构：单端口双角色（面板流量按路径区分：`/`、`/api/*`、`/ws`；CONNECT 及其余请求走代理逻辑）
- 代理核心：CONNECT 隧道 → 动态签发域名证书（内置 CA）→ 解密明文 → 转发上游 → 流量入内存环形缓冲
- 面板：零构建静态文件（panel.html / app.js / style.css，原生 JS），WebSocket 实时推送 + REST 查询
- 前后端一体：同工程、同进程交付

## 4. 面板接口清单（M1）

| 接口 | 作用 |
|---|---|
| `WS /ws` | 实时推送新流量事件 |
| `GET /api/traffic?domain=&page=` | 流量列表（域名过滤、分页） |
| `GET /api/traffic/{id}` | 请求/响应详情 |
| `GET /api/cert` | 下载根证书（引导安装 CurrentUser\Root） |
| `GET/POST/DELETE /api/rules` | 域名级 Header 注入规则（M2 预留，M1 不实现） |

## 5. 工程评估结论

- 工程：baohulu 单仓库（E:\myGit\baohulu），从零搭建；无存量代码、无数据库、无外部工程依赖
- 变更性质：设计（全新模块+接口）；影响层级：代码层+接口层；前后端配套：否（一体交付）
- 环境注意项：
  - 命令行 `JAVA_HOME` 当前为 JDK 8，构建须会话级指定 GraalVM 21（`D:/java/graalvm-jdk-21.0.12+7.1`，不修改全局环境变量）
  - Maven 平台编码为 GBK，pom 必须显式声明 `project.build.sourceEncoding=UTF-8`
- 已知风险（M1 相关）：
  - 设置系统代理后需重启微信（WeChatAppEx 进程重载代理配置）才生效
  - 工具退出必须还原系统代理（shutdown hook + 启动自检）
  - 响应体设截断上限（默认 1MB）防内存膨胀；二进制仅显示类型与大小

## 6. M1 验收标准

1. 设置系统代理指向本工具（重启微信）后，小程序 HTTPS 流量在面板实时可见（明文）
2. 域名过滤生效；详情含完整请求头/参数/响应头/响应体，JSON 自动格式化
3. 工具退出后系统代理还原
4. Maven（GraalVM 21）可构建可运行 fat-jar，`java -jar` 启动即用
