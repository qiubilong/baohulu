package com.baohulu.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.baohulu.cert.CaCertManager;
import com.baohulu.cert.DynamicCertManager;
import com.baohulu.panel.PanelRouteHandler;
import com.baohulu.proxy.GrayHeaderModifier;
import com.baohulu.proxy.UpstreamForwarder;
import com.baohulu.traffic.TrafficStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpServer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * 代理服务集成测试：进程内真实装配（Netty + 面板 + 转发），
 * 以 JDK HttpServer 做内嵌上游、JDK HttpClient/WebSocket 做客户端，零外网依赖
 *
 * @author qiubilong
 * @date 2026-11-12
 */
class ProxyServerIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static EventLoopGroup bossGroup;
    private static EventLoopGroup workerGroup;
    private static Channel serverChannel;
    private static HttpServer upstream;
    private static TrafficStore trafficStore;
    private static int proxyPort;
    private static int upstreamPort;

    /** 直连客户端（访问面板） */
    private static HttpClient directClient;

    /** 走代理客户端（转发流量） */
    private static HttpClient proxyClient;

    /** 最近一次内嵌上游收到的请求头（多值逗号合并，供剥离/注入断言） */
    private static volatile Map<String, String> upstreamSeenHeaders = Map.of();

    @BeforeAll
    static void startAll(@TempDir Path tempDir) throws Exception {
        // 内嵌上游：任意路径返回 JSON，/binary 返回二进制（验二进制标记）
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            Map<String, String> merged = new java.util.LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> merged.put(k, String.join(",", v)));
            upstreamSeenHeaders = Map.copyOf(merged);
            if (exchange.getRequestURI().getPath().endsWith("/binary")) {
                byte[] body = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A};
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            byte[] body = ("{\"ok\":true,\"path\":\"" + exchange.getRequestURI().getPath() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        upstream.start();
        upstreamPort = upstream.getAddress().getPort();

        // baohulu 真实装配链（与 BaohuluMain 一致，随机端口）
        trafficStore = new TrafficStore(50);
        CaCertManager caCertManager = new CaCertManager(tempDir);
        DynamicCertManager dynamicCertManager = new DynamicCertManager(caCertManager);
        GrayHeaderModifier grayHeaderModifier = new GrayHeaderModifier();
        UpstreamForwarder forwarder = new UpstreamForwarder(trafficStore, grayHeaderModifier, 1024);

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ProxyServerInitializer(dynamicCertManager, forwarder,
                        () -> new PanelRouteHandler(trafficStore, caCertManager, grayHeaderModifier, 0)));
        serverChannel = bootstrap.bind("127.0.0.1", 0).sync().channel();
        proxyPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();

        directClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
        proxyClient = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", proxyPort)))
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
    }

    @AfterAll
    static void stopAll() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + proxyPort + path))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 经代理请求内嵌上游 */
    private HttpResponse<String> getViaProxy(String pathAndQuery) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + upstreamPort + pathAndQuery))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build();
        return proxyClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void tc_i01_panel_static_resources() throws Exception {
        HttpResponse<String> page = get(directClient, "/");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("baohulu"), "面板首页应含标题");
        assertTrue(page.headers().firstValue("Content-Type").orElse("").contains("text/html"));

        assertEquals(200, get(directClient, "/app.js").statusCode());
        assertEquals(200, get(directClient, "/style.css").statusCode());
        assertTrue(get(directClient, "/app.js").headers().firstValue("Content-Type").orElse("")
                .contains("javascript"));
    }

    @Test
    void tc_i02_traffic_list_empty_structure() throws Exception {
        // 依赖执行顺序存在风险，本用例只断言结构字段存在
        Map<String, Object> body = JSON.readValue(get(directClient, "/api/traffic").body(), Map.class);
        assertNotNull(body.get("total"));
        assertEquals(1, body.get("page"));
        assertNotNull(body.get("list"));
    }

    @Test
    void tc_i03_proxy_forward_and_capture() throws Exception {
        HttpResponse<String> response = getViaProxy("/echo?q=1");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"ok\":true"), "上游响应体应透传");

        // 捕获断言：host / path / query / status
        Map<String, Object> body = JSON.readValue(get(directClient, "/api/traffic").body(), Map.class);
        List<Map<String, Object>> list = (List<Map<String, Object>>) body.get("list");
        Map<String, Object> captured = list.stream()
                .filter(e -> "/echo".equals(e.get("path")))
                .findFirst().orElseThrow(() -> new AssertionError("未捕获 /echo 请求"));
        assertEquals("127.0.0.1", captured.get("host"));
        assertEquals("q=1", captured.get("query"));
        assertEquals(200, captured.get("status"));
        assertNotNull(captured.get("url"));
    }

    @Test
    void tc_i04_traffic_detail_fields() throws Exception {
        getViaProxy("/detail");
        Map<String, Object> body = JSON.readValue(get(directClient, "/api/traffic").body(), Map.class);
        List<Map<String, Object>> list = (List<Map<String, Object>>) body.get("list");
        Map<String, Object> captured = list.stream()
                .filter(e -> "/detail".equals(e.get("path")))
                .findFirst().orElseThrow(AssertionError::new);
        long id = ((Number) captured.get("id")).longValue();

        HttpResponse<String> detailResp = get(directClient, "/api/traffic/" + id);
        assertEquals(200, detailResp.statusCode());
        Map<String, Object> detail = JSON.readValue(detailResp.body(), Map.class);
        Map<String, Object> requestHeaders = (Map<String, Object>) detail.get("requestHeaders");
        assertNotNull(requestHeaders, "详情应含请求头");
        assertNotNull(requestHeaders.get("Host"), "请求头应含 Host");
        assertNotNull(detail.get("responseBody"), "详情应含响应体");
        assertNotNull(detail.get("responseContentType"), "详情应含响应类型");
        assertNotNull(detail.get("durationMs"), "详情应含耗时");
    }

    @Test
    void tc_i05_clear_method_guard_and_effect() throws Exception {
        getViaProxy("/before-clear");
        // GET 应被拒绝 405
        assertEquals(405, get(directClient, "/api/clear").statusCode());

        HttpRequest post = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + proxyPort + "/api/clear"))
                .timeout(java.time.Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> cleared = directClient.send(post, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cleared.statusCode());

        Map<String, Object> body = JSON.readValue(get(directClient, "/api/traffic").body(), Map.class);
        assertEquals(0, ((Number) body.get("total")).intValue(), "POST 清空后应无流量");
    }

    @Test
    void tc_i07_websocket_push() throws Exception {
        CountDownLatch messageLatch = new CountDownLatch(1);
        CountDownLatch pongLatch = new CountDownLatch(1);
        CompletableFuture<String> received = new CompletableFuture<>();
        StringBuilder buffer = new StringBuilder();
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                webSocket.request(1);
                if (last) {
                    received.complete(buffer.toString());
                    messageLatch.countDown();
                }
                return null;
            }

            @Override
            public CompletionStage<?> onPong(WebSocket webSocket, java.nio.ByteBuffer message) {
                // JDK WebSocket 背压协议：每次回调后需 request 才会继续回调
                webSocket.request(1);
                pongLatch.countDown();
                return null;
            }
        };
        WebSocket webSocket = directClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + proxyPort + "/ws"), listener)
                .get(5, TimeUnit.SECONDS);

        // 心跳：服务端应回 Pong
        webSocket.sendPing(java.nio.ByteBuffer.wrap("ping".getBytes(StandardCharsets.UTF_8))).get(5, TimeUnit.SECONDS);
        assertTrue(pongLatch.await(5, TimeUnit.SECONDS), "应收到 Pong 心跳响应");

        // 触发一条新流量，等待 WS 推送
        getViaProxy("/ws-trigger");
        assertTrue(messageLatch.await(5, TimeUnit.SECONDS), "5 秒内应收到 WS 推送");
        Map<String, Object> pushed = JSON.readValue(received.get(), Map.class);
        assertEquals("/ws-trigger", pushed.get("path"));
        assertEquals("GET", pushed.get("method"));
        assertNotNull(pushed.get("id"));

        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
    }

    @Test
    void tc_i08_domain_filter_server_side() throws Exception {
        getViaProxy("/filter-a");
        Map<String, Object> noHit = JSON.readValue(
                get(directClient, "/api/traffic?domain=nomatch.invalid").body(), Map.class);
        assertEquals(0, ((Number) noHit.get("total")).intValue(), "无命中域名应过滤为空");

        Map<String, Object> all = JSON.readValue(get(directClient, "/api/traffic").body(), Map.class);
        assertTrue(((Number) all.get("total")).intValue() > 0, "无过滤参数应返回全部");
    }

    /**
     * CONNECT 隧道 MITM 链路：动态证书握手成功后，上游不可达应回 502。
     * 客户端 TrustManager 信任所有（只验证 baohulu 侧 SNI 签发与服务端 TLS 挂载，不校验证书链）
     */
    @Test
    void tc_i09_connect_mitm_handshake_and_upstream_failure() throws Exception {
        // 借一个已关闭端口作为 CONNECT 目标（握手可完成，转发必失败）
        int closedPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
            probe.bind(new InetSocketAddress("127.0.0.1", 0));
            closedPort = probe.getLocalPort();
        }

        try (java.net.Socket raw = new java.net.Socket("127.0.0.1", proxyPort)) {
            raw.setSoTimeout(10000);
            OutputStream out = raw.getOutputStream();
            out.write(("CONNECT 127.0.0.1:" + closedPort + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + closedPort + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            // 逐行读到空行，断言 200 Connection Established
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(raw.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = reader.readLine();
            assertEquals("HTTP/1.1 200", statusLine.substring(0, "HTTP/1.1 200".length()),
                    "CONNECT 应回 200");
            String line;
            do {
                line = reader.readLine();
            } while (line != null && !line.isEmpty());

            // 包装 TLS（信任所有）：验证 SNI 兑底→无 SNI 回退 CONNECT host 签发与服务端 SslHandler 挂载
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }};
            javax.net.ssl.SSLContext tlsContext = javax.net.ssl.SSLContext.getInstance("TLS");
            tlsContext.init(null, trustAll, new java.security.SecureRandom());
            javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket) tlsContext.getSocketFactory()
                    .createSocket(raw, "127.0.0.1", closedPort, false);
            tls.startHandshake();

            // 握手成功：MITM 解密链路（CONNECT→SNI→动态证书→SslHandler）全部生效
            tls.getOutputStream().write(("GET /mitm-fail HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            tls.getOutputStream().flush();
            java.io.BufferedReader tlsReader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(tls.getInputStream(), StandardCharsets.ISO_8859_1));
            String respLine = tlsReader.readLine();
            assertNotNull(respLine, "上游不可达应回写 HTTP 响应");
            assertTrue(respLine.contains("502"), "上游不可达应回 502，实际: " + respLine);
            tls.close();
        }
    }

    /** 二进制响应仅记类型与大小，不保存文本 */
    @Test
    void tc_i10_binary_response_capture() throws Exception {
        getViaProxy("/pic/binary");
        Map<String, Object> body = JSON.readValue(get(directClient, "/api/traffic").body(), Map.class);
        List<Map<String, Object>> list = (List<Map<String, Object>>) body.get("list");
        Map<String, Object> captured = list.stream()
                .filter(e -> "/pic/binary".equals(e.get("path")))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("image/png", captured.get("responseContentType"));
        assertEquals(6, ((Number) captured.get("responseSize")).intValue());

        long id = ((Number) captured.get("id")).longValue();
        Map<String, Object> detail = JSON.readValue(
                get(directClient, "/api/traffic/" + id).body(), Map.class);
        assertEquals(Boolean.TRUE, detail.get("responseBinary"), "二进制响应应置标记");
    }

    /**
     * 经代理转发应剥离压缩协商头（大小写不敏感）：上游不应收到任何形态的 Accept-Encoding，
     * 否则上游返回 gzip 压缩响应，面板响应体将乱码、JSON 无法格式化
     */
    @Test
    void tc_i11_accept_encoding_stripped_case_insensitive() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + upstreamPort + "/gzip-check"))
                .timeout(java.time.Duration.ofSeconds(10))
                // 模拟 Chromium 混合大小写发送，覆盖大小写敏感剥离缺陷
                .header("Accept-Encoding", "gzip, deflate, br")
                .GET().build();
        HttpResponse<String> response = proxyClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(upstreamSeenHeaders.keySet().stream().noneMatch(
                        name -> name.equalsIgnoreCase("accept-encoding")),
                "上游不应收到任何大小写形态的 Accept-Encoding, 实际: " + upstreamSeenHeaders.keySet());
    }

    /**
     * 灰度标记注入：面板开关实时生效——默认关闭不注入，开启后上游收到 grayreleasetag=gray，
     * 关闭后恢复不注入
     */
    @Test
    void tc_i12_gray_header_toggle_and_inject() throws Exception {
        // 默认关闭
        Map<String, Object> state = JSON.readValue(get(directClient, "/api/gray").body(), Map.class);
        assertEquals(false, state.get("enabled"));
        getViaProxy("/gray-off");
        assertTrue(upstreamSeenHeaders.keySet().stream()
                        .noneMatch(n -> n.equalsIgnoreCase("grayreleasetag")),
                "默认关闭不应注入, 实际: " + upstreamSeenHeaders.keySet());

        // 面板开启
        HttpRequest toggle = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + proxyPort + "/api/gray/toggle"))
                .timeout(java.time.Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(true, JSON.readValue(
                directClient.send(toggle, HttpResponse.BodyHandlers.ofString()).body(), Map.class).get("enabled"));

        getViaProxy("/gray-on");
        boolean hit = upstreamSeenHeaders.entrySet().stream().anyMatch(e ->
                e.getKey().equalsIgnoreCase("grayreleasetag") && "gray".equals(e.getValue()));
        assertTrue(hit, "开启后上游应收到 grayreleasetag=gray, 实际: " + upstreamSeenHeaders);

        // 面板详情的请求头应展示注入后的最终形态（发往上游视角），否则用户无法在面板确认注入生效
        Map<String, Object> grayEntry = ((List<Map<String, Object>>) JSON.readValue(
                        get(directClient, "/api/traffic").body(), Map.class).get("list")).stream()
                .filter(e -> "/gray-on".equals(e.get("path")))
                .findFirst().orElseThrow(() -> new AssertionError("未捕获 /gray-on"));
        Map<String, Object> detailHeaders = (Map<String, Object>) JSON.readValue(
                get(directClient, "/api/traffic/" + ((Number) grayEntry.get("id")).longValue()).body(),
                Map.class).get("requestHeaders");
        assertEquals("gray", detailHeaders.get("grayreleasetag"),
                "面板请求头应含注入的 grayreleasetag=gray");

        // 再次切换关闭后恢复不注入
        assertEquals(false, JSON.readValue(
                directClient.send(toggle, HttpResponse.BodyHandlers.ofString()).body(), Map.class).get("enabled"));
        getViaProxy("/gray-off-again");
        assertTrue(upstreamSeenHeaders.keySet().stream()
                        .noneMatch(n -> n.equalsIgnoreCase("grayreleasetag")),
                "关闭后应恢复不注入, 实际: " + upstreamSeenHeaders.keySet());
    }
}
