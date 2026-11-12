# baohulu【宝葫芦】

web http 监听查看、定制请求实验工具。

本地 MITM 代理，用于监听/查看微信 PC 端小程序的 HTTPS 明文流量：系统代理接管 → CONNECT 隧道动态证书解密 → 上游转发 → 浏览器面板实时查看。

## 功能（M1）

- HTTPS 解密：首次运行动态生成 CA（`~/.baohulu/ca/`），按域名实时签发叶子证书
- 流量列表：WebSocket 实时推送，最近 1000 条内存环形缓冲
- 域名过滤：默认仅看业务域名 `ifengqun.com`，工具栏「全部域名 / 仅业务域名」一键切换，输入框也可手输任意域名即时过滤
- 详情查看：请求/响应头、请求/响应体，JSON 自动格式化高亮
- 系统代理：启动自动接管，退出自动还原（注册表 HKCU Internet Settings）；哨兵进程兜底，强杀也能还原
- 面板：浏览器访问 `http://127.0.0.1:8899/`，单端口双角色（代理 + 面板）
- 灰度标记：面板「灰度」开关实时生效，开启后所有上游请求注入 `grayreleasetag: gray` 请求头（客户端自带同款头时不覆盖），用于将小程序流量路由到灰度环境

> 域名级 Header 注入规则（按域名定制多个 Header）为 M2 后续范围，当前已落地全局灰度标记开关。

## 快速开始

### 1. 构建与启动

```powershell
# 构建前会话内指定 JDK 21（命令行默认可能是 JDK 8）
$env:JAVA_HOME='D:\java\graalvm-jdk-21.0.12+7.1'
$env:Path="$env:JAVA_HOME\bin;$env:Path"

mvn clean package -DskipTests
java -jar target/baohulu.jar
```

启动后日志显示监听 `127.0.0.1:8899`，并自动将系统代理指向本工具。

### 2. 安装 CA 证书（仅首次，解密 HTTPS 必需）

**方式一（推荐）：一键安装**

1. 打开面板 `http://127.0.0.1:8899/`
2. 点击工具栏「一键安装CA」，若屏幕弹出安全确认框请点「是」
3. 提示安装成功后，重启浏览器/微信生效

**方式二：手动安装**（一键安装不可用时的备选）

证书文件位于 `%USERPROFILE%\.baohulu\ca\ca-cert.pem`，命令行安装：

```powershell
certutil -addstore -user Root "%USERPROFILE%\.baohulu\ca\ca-cert.pem"
```

如需图形向导：双击该 PEM 文件 → 安装证书 → 存储位置选**本地计算机** → **切勿选「自动选择存储」**（自签 CA 常被误装入「中级证书颁发机构」导致信任不生效）→ 手动指定 **受信任的根证书颁发机构**。

微信小程序流量无证书绑定（SSL Pinning），信任 baohulu CA 后即可解密。

### 3. 设置系统代理（默认已自动完成）

工具启动时已自动接管系统代理并指向 `127.0.0.1:8899`，退出时自动还原。

如需手动控制：Windows「设置 → 网络和 Internet → 代理」手动配置，或以
`-Dbaohulu.auto.proxy=false` 启动关闭自动接管。

### 4. 重启微信

系统代理修改后，微信（含小程序容器 WeChatAppEx）需 **完全退出再启动** 才会读取新代理。

### 5. 查看流量

打开 `http://127.0.0.1:8899/`：

- 左侧实时流量列表，点击行查看详情
- 顶部输入域名关键词即时过滤
- 「暂停/继续」冻结列表刷新，「清空」一键清空
- 「灰度」开关切换后实时生效，详情区「请求头」Tab 可确认上游已收到 `grayreleasetag: gray`
- 详情区右上「复制URL」/「复制cURL」：cURL 为 CMD 兼容单行命令（含方法/请求头/请求体），Postman Import → Raw text 粘贴可直接导入重放，CMD 也可直接执行
- 详情区切换 概览 / 请求头 / 请求体 / 响应头 / 响应体 Tab，JSON 自动格式化

## 配置项

通过系统属性覆盖（`-D键=值`）：

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `baohulu.port` | `8899` | 监听端口（代理 + 面板共用） |
| `baohulu.buffer.capacity` | `1000` | 流量环形缓冲条数 |
| `baohulu.body.max.kb` | `1024` | 请求/响应体保留上限（KB），超出截断 |
| `baohulu.auto.proxy` | `true` | 是否自动接管/还原系统代理 |

## 常见问题

- **看不到流量？** 确认：CA 已安装到「受信任的根证书颁发机构」；微信完全退出后重启过；系统代理指向 `127.0.0.1:8899`。
- **列表显示 ERR？** 上游转发失败（目标不可达/证书异常），详情区「错误」字段有原因。
- **响应体显示二进制？** 图片等非文本内容仅记录类型与大小，不保留内容。
- **异常退出后系统代理残留？** 工具接管代理时会拉起独立还原哨兵：即使被强杀（任务管理器结束进程、点 X 关窗口），哨兵也会在数秒内自动还原系统代理；若哨兵也失效，可执行：`reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyEnable /t REG_DWORD /d 0 /f`

## 技术栈

Java 21（GraalVM）+ Netty 4.1 + BouncyCastle + Jackson + 原生 JS 面板（零构建）。
