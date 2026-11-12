package com.baohulu.panel;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baohulu.cert.CaCertManager;
import com.baohulu.traffic.TrafficEntry;
import com.baohulu.traffic.TrafficStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.CharsetUtil;

/**
 * 面板路由处理器
 * 承载面板直连流量：静态资源（classpath web/）、REST API（流量列表/详情/证书下载/清空）、
 * WebSocket 升级（/ws，实时流量推送）
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public class PanelRouteHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(PanelRouteHandler.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** WebSocket 单帧最大长度：1MB */
    private static final int WS_MAX_FRAME_SIZE = 1024 * 1024;

    /** 静态资源缓存：文件名 -> 内容字节（随 jar 打包，启动时一次加载） */
    private static final Map<String, byte[]> STATIC_RESOURCES = new ConcurrentHashMap<>(8);

    /** 静态资源 Content-Type 映射 */
    private static final Map<String, String> STATIC_CONTENT_TYPES = Map.of(
            "panel.html", "text/html; charset=utf-8",
            "app.js", "application/javascript; charset=utf-8",
            "style.css", "text/css; charset=utf-8");

    static {
        loadStaticResource("panel.html");
        loadStaticResource("app.js");
        loadStaticResource("style.css");
    }

    private final TrafficStore trafficStore;

    private final CaCertManager caCertManager;

    private final int port;

    public PanelRouteHandler(TrafficStore trafficStore, CaCertManager caCertManager, int port) {
        this.trafficStore = trafficStore;
        this.caCertManager = caCertManager;
        this.port = port;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        QueryStringDecoder queryString = new QueryStringDecoder(request.uri());
        String path = queryString.path();
        if ("/ws".equals(path)) {
            handleWebSocket(ctx, request);
            return;
        }
        switch (path) {
            case "/", "/panel.html" -> serveStatic(ctx, request, "panel.html");
            case "/app.js" -> serveStatic(ctx, request, "app.js");
            case "/style.css" -> serveStatic(ctx, request, "style.css");
            case "/api/traffic" -> handleTrafficList(ctx, request, queryString);
            case "/api/cert" -> handleCertDownload(ctx, request);
            case "/api/clear" -> handleClear(ctx, request);
            default -> {
                if (path.startsWith("/api/traffic/")) {
                    handleTrafficDetail(ctx, request, path);
                } else {
                    writeJson(ctx, request, HttpResponseStatus.NOT_FOUND,
                            Map.of("error", "not found: " + path));
                }
            }
        }
    }

    /**
     * 流量列表：GET /api/traffic?domain=xxx&page=1
     */
    private void handleTrafficList(ChannelHandlerContext ctx, FullHttpRequest request,
                                   QueryStringDecoder queryString) {
        String domain = queryParam(queryString, "domain");
        String pageText = queryParam(queryString, "page");
        int page = 1;
        if (pageText != null && !pageText.isBlank()) {
            try {
                page = Integer.parseInt(pageText);
            } catch (NumberFormatException e) {
                writeJson(ctx, request, HttpResponseStatus.BAD_REQUEST, Map.of("error", "page 必须为数字"));
                return;
            }
        }
        int total = trafficStore.queryTrafficCount(domain);
        List<Map<String, Object>> list = trafficStore.queryTrafficList(domain, page).stream()
                .map(PanelRouteHandler::toSummary)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>(8);
        result.put("total", total);
        result.put("page", page);
        result.put("list", list);
        writeJson(ctx, request, HttpResponseStatus.OK, result);
    }

    /**
     * 流量详情：GET /api/traffic/{id}
     */
    private void handleTrafficDetail(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        String idText = path.substring("/api/traffic/".length());
        long id;
        try {
            id = Long.parseLong(idText);
        } catch (NumberFormatException e) {
            writeJson(ctx, request, HttpResponseStatus.BAD_REQUEST, Map.of("error", "id 必须为数字"));
            return;
        }
        TrafficEntry entry = trafficStore.getTrafficById(id);
        if (entry == null) {
            writeJson(ctx, request, HttpResponseStatus.NOT_FOUND, Map.of("error", "流量不存在或已淘汰: " + id));
            return;
        }
        writeJson(ctx, request, HttpResponseStatus.OK, entry);
    }

    /**
     * CA 证书下载：GET /api/cert（安装到系统「受信任的根证书颁发机构」）
     */
    private void handleCertDownload(ChannelHandlerContext ctx, FullHttpRequest request) {
        byte[] pemBytes = caCertManager.getCaCertificatePem().getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK, Unpooled.wrappedBuffer(pemBytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-x509-ca-cert");
        response.headers().set("Content-Disposition", "attachment; filename=baohulu-ca.crt");
        HttpUtil.setContentLength(response, pemBytes.length);
        writeResponse(ctx, request, response);
    }

    /**
     * 清空流量：POST /api/clear（仅 POST，防 GET 误触与跨页 CSRF 骚扰清空）
     */
    private void handleClear(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!io.netty.handler.codec.http.HttpMethod.POST.equals(request.method())) {
            writeJson(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "仅支持 POST"));
            return;
        }
        trafficStore.clearTraffic();
        writeJson(ctx, request, HttpResponseStatus.OK, Map.of("ok", true));
    }

    /**
     * WebSocket 升级：GET /ws（101 完成后切换为推送管线）
     */
    private void handleWebSocket(ChannelHandlerContext ctx, FullHttpRequest request) {
        WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                "ws://127.0.0.1:" + port + "/ws", null, false, WS_MAX_FRAME_SIZE);
        WebSocketServerHandshaker handshaker = factory.newHandshaker(request);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            return;
        }
        handshaker.handshake(ctx.channel(), request).addListener(future -> {
            if (!future.isSuccess()) {
                log.warn("WebSocket 握手失败, remote={}", ctx.channel().remoteAddress(), future.cause());
                ctx.close();
                return;
            }
            // 101 已写出：移除 HTTP 编解码，切换为纯 WS 推送管线
            ChannelPipeline pipeline = ctx.channel().pipeline();
            removeIfPresent(pipeline, HttpObjectAggregator.class);
            removeIfPresent(pipeline, HttpServerCodec.class);
            pipeline.addLast(new WsPushHandler(trafficStore));
            pipeline.remove(this);
            log.debug("面板 WS 连接建立, remote={}", ctx.channel().remoteAddress());
        });
    }

    /**
     * 静态资源响应
     */
    private void serveStatic(ChannelHandlerContext ctx, FullHttpRequest request, String fileName) {
        byte[] content = STATIC_RESOURCES.get(fileName);
        if (content == null) {
            writeJson(ctx, request, HttpResponseStatus.NOT_FOUND, Map.of("error", "资源不存在: " + fileName));
            return;
        }
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK, Unpooled.wrappedBuffer(content));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, STATIC_CONTENT_TYPES.get(fileName));
        HttpUtil.setContentLength(response, content.length);
        writeResponse(ctx, request, response);
    }

    /**
     * 对象序列化为 JSON 响应
     */
    private void writeJson(ChannelHandlerContext ctx, FullHttpRequest request,
                           HttpResponseStatus status, Object body) {
        byte[] jsonBytes;
        try {
            jsonBytes = OBJECT_MAPPER.writeValueAsBytes(body);
        } catch (IOException e) {
            log.error("JSON 序列化失败, bodyType={}", body.getClass().getName(), e);
            jsonBytes = "{\"error\":\"serialize failed\"}".getBytes(CharsetUtil.UTF_8);
        }
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(jsonBytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        HttpUtil.setContentLength(response, jsonBytes.length);
        writeResponse(ctx, request, response);
    }

    /**
     * 按 keep-alive 语义写出响应
     */
    private void writeResponse(ChannelHandlerContext ctx, FullHttpRequest request,
                               FullHttpResponse response) {
        if (HttpUtil.isKeepAlive(request)) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * 流量精简摘要（列表与 WS 推送共用，详情按需单独拉取）
     */
    static Map<String, Object> toSummary(TrafficEntry entry) {
        Map<String, Object> summary = new LinkedHashMap<>(16);
        summary.put("id", entry.getId());
        summary.put("startTime", entry.getStartTime());
        summary.put("method", entry.getMethod());
        summary.put("host", entry.getHost());
        summary.put("path", entry.getPath());
        summary.put("query", entry.getQuery());
        summary.put("url", entry.getUrl());
        summary.put("status", entry.getStatus());
        summary.put("durationMs", entry.getDurationMs());
        summary.put("responseSize", entry.getResponseSize());
        summary.put("responseContentType", entry.getResponseContentType());
        summary.put("error", entry.getError());
        return summary;
    }

    private static String queryParam(QueryStringDecoder queryString, String name) {
        List<String> values = queryString.parameters().get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private static void removeIfPresent(ChannelPipeline pipeline,
                                        Class<? extends io.netty.channel.ChannelHandler> clazz) {
        if (pipeline.get(clazz) != null) {
            pipeline.remove(clazz);
        }
    }

    /**
     * 启动时从 classpath 加载静态资源到内存缓存
     */
    private static void loadStaticResource(String fileName) {
        try (InputStream input = PanelRouteHandler.class.getResourceAsStream("/web/" + fileName)) {
            if (input == null) {
                throw new IllegalStateException("面板静态资源缺失: /web/" + fileName);
            }
            STATIC_RESOURCES.put(fileName, input.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("加载面板静态资源失败: " + fileName, e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("面板连接处理异常, remote={}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
