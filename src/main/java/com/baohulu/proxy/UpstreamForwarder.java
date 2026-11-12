package com.baohulu.proxy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baohulu.traffic.TrafficEntry;
import com.baohulu.traffic.TrafficStore;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

/**
 * 上游转发器
 * 将解密后的明文请求经 JDK HttpClient 转发到真实服务器，响应回写小程序侧，
 * 并将完整请求/响应捕获进流量存储（body 超限截断、二进制仅记类型与大小）
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public class UpstreamForwarder {

    private static final Logger log = LoggerFactory.getLogger(UpstreamForwarder.class);

    /** 逐跳头：代理场景不透传给上游 */
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "proxy-connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");

    /** JDK HttpClient 受限头：由其自动处理，手动设置会被忽略 */
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "host", "content-length", "expect", "upgrade");

    /** 文本类 Content-Type 关键字（命中则保存文本，否则按二进制处理） */
    private static final Set<String> TEXTUAL_CONTENT_KEYWORDS = Set.of(
            "text/", "json", "xml", "javascript", "x-www-form-urlencoded", "css", "svg");

    /** 请求开始时间格式 */
    private static final DateTimeFormatter START_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 上游连接超时 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 上游请求整体超时 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;

    private final TrafficStore trafficStore;

    private final HeaderModifier headerModifier;

    private final int bodyMaxBytes;

    public UpstreamForwarder(TrafficStore trafficStore, HeaderModifier headerModifier, int bodyMaxKb) {
        this.trafficStore = trafficStore;
        this.headerModifier = headerModifier;
        this.bodyMaxBytes = bodyMaxKb * 1024;
        this.httpClient = HttpClient.newBuilder()
                // 强制 HTTP/1.1：避免 ALPN 协商开销与响应语义差异，回写客户端保持一致
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                // 代理透传原始响应，不跟随重定向
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 转发单条请求并异步回写响应
     * 注意：body 字节必须在调用线程内完成复制（Netty 消息随方法返回被释放）
     *
     * @param clientRequest 小程序侧的明文请求（MITM 解密后）
     * @param scheme 目标协议（http/https）
     * @param hostWithPort 目标主机（host:port 形式，port 可省略）
     * @param relativeUri 相对 URI（path[?query]）
     * @param clientChannel 小程序侧连接，用于回写响应
     */
    public void forward(FullHttpRequest clientRequest, String scheme, String hostWithPort,
                        String relativeUri, Channel clientChannel) {
        long startNanos = System.nanoTime();
        // 同步提取请求字节，避免异步访问已释放的 ByteBuf
        byte[] requestBodyBytes = readContentBytes(clientRequest.content());
        TrafficEntry entry = buildRequestEntry(clientRequest, scheme, hostWithPort,
                relativeUri, requestBodyBytes);
        String targetUrl = buildTargetUrl(scheme, hostWithPort, relativeUri);
        HttpRequest upstreamRequest;
        try {
            upstreamRequest = buildUpstreamRequest(clientRequest, targetUrl, requestBodyBytes);
        } catch (Exception e) {
            log.error("构建上游请求失败, url={}", targetUrl, e);
            entry.setError("构建上游请求失败: " + e.getMessage());
            trafficStore.addTraffic(entry);
            writeFailureResponse(clientRequest, clientChannel);
            return;
        }
        httpClient.sendAsync(upstreamRequest, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        log.warn("上游转发失败, url={}, cause={}", targetUrl, throwable.getMessage());
                        entry.setError("转发失败: " + throwable.getMessage());
                        trafficStore.addTraffic(entry);
                        writeFailureResponse(clientRequest, clientChannel);
                    } else {
                        entry.setDurationMs((System.nanoTime() - startNanos) / 1_000_000);
                        captureResponse(entry, response);
                        trafficStore.addTraffic(entry);
                        writeResponse(clientRequest, clientChannel, response);
                    }
                });
    }

    /**
     * 构建流量记录的请求部分
     */
    private TrafficEntry buildRequestEntry(FullHttpRequest request, String scheme, String hostWithPort,
                                           String relativeUri, byte[] requestBodyBytes) {
        TrafficEntry entry = new TrafficEntry();
        entry.setStartTime(START_TIME_FORMATTER.format(LocalDateTime.now()));
        entry.setMethod(request.method().name());
        entry.setHost(stripPort(hostWithPort));
        entry.setUrl(buildTargetUrl(scheme, hostWithPort, relativeUri));
        String uri = relativeUri;
        int queryIndex = uri.indexOf('?');
        if (queryIndex >= 0) {
            entry.setPath(uri.substring(0, queryIndex));
            entry.setQuery(uri.substring(queryIndex + 1));
        } else {
            entry.setPath(uri);
        }
        entry.setRequestHeaders(toHeaderMap(request.headers()));
        String requestContentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        entry.setRequestContentType(requestContentType);
        captureBodyText(requestBodyBytes, requestContentType, bodyMaxBytes, false, entry);
        return entry;
    }

    /**
     * 构建上游请求：复制头（剔除逐跳头与 Accept-Encoding），应用 M2 Header 拦截点，附带 body
     */
    private HttpRequest buildUpstreamRequest(FullHttpRequest clientRequest, String targetUrl,
                                             byte[] requestBodyBytes) {
        Map<String, String> headers = toHeaderMap(clientRequest.headers());
        headers.keySet().removeIf(name -> HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT)));
        // 移除压缩协商：让上游返回未压缩明文，面板展示免解压
        headers.remove("accept-encoding");
        // M2 拦截点：域名级 Header 注入
        headerModifier.modifyHeaders(hostOf(targetUrl), headers);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(targetUrl))
                .timeout(REQUEST_TIMEOUT)
                .method(clientRequest.method().name(), HttpRequest.BodyPublishers.ofByteArray(requestBodyBytes));
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (RESTRICTED_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            builder.header(header.getKey(), header.getValue());
        }
        return builder.build();
    }

    /**
     * 捕获响应到流量记录（二进制仅记类型与大小，文本超限截断）
     */
    private void captureResponse(TrafficEntry entry, HttpResponse<byte[]> response) {
        entry.setStatus(response.statusCode());
        entry.setResponseHeaders(response.headers().map().entrySet().stream()
                .collect(LinkedHashMap::new,
                        (map, e) -> map.put(e.getKey(), String.join(",", e.getValue())),
                        Map::putAll));
        byte[] bodyBytes = response.body() == null ? new byte[0] : response.body();
        entry.setResponseSize(bodyBytes.length);
        String contentType = response.headers().firstValue("content-type").orElse(null);
        entry.setResponseContentType(contentType);
        captureBodyText(bodyBytes, contentType, bodyMaxBytes, true, entry);
    }

    /**
     * 文本化保存 body（请求或响应），二进制内容置标记不保存文本
     */
    private void captureBodyText(byte[] bodyBytes, String contentType, int maxBytes,
                                 boolean isResponse, TrafficEntry entry) {
        if (bodyBytes.length == 0) {
            return;
        }
        boolean textual = isTextualContentType(contentType);
        if (isResponse && !textual) {
            entry.setResponseBinary(true);
            return;
        }
        byte[] savedBytes = bodyBytes;
        boolean truncated = false;
        if (bodyBytes.length > maxBytes) {
            savedBytes = Arrays.copyOfRange(bodyBytes, 0, maxBytes);
            truncated = true;
        }
        String text = new String(savedBytes, resolveCharset(contentType));
        if (isResponse) {
            entry.setResponseBody(text);
            entry.setResponseTruncated(truncated);
        } else {
            entry.setRequestBody(text);
            entry.setRequestTruncated(truncated);
        }
    }

    /**
     * 回写上游响应到小程序侧（保持 keep-alive 语义）
     */
    private void writeResponse(FullHttpRequest clientRequest, Channel clientChannel,
                               HttpResponse<byte[]> response) {
        byte[] bodyBytes = response.body() == null ? new byte[0] : response.body();
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(response.statusCode()),
                Unpooled.wrappedBuffer(bodyBytes));
        for (Map.Entry<String, java.util.List<String>> header : response.headers().map().entrySet()) {
            String name = header.getKey();
            if (HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                    || "content-length".equalsIgnoreCase(name)) {
                continue;
            }
            nettyResponse.headers().set(name, String.join(",", header.getValue()));
        }
        HttpUtil.setContentLength(nettyResponse, bodyBytes.length);
        writeWithKeepAlive(clientRequest, clientChannel, nettyResponse);
    }

    /**
     * 回写 502 失败响应
     */
    private void writeFailureResponse(FullHttpRequest clientRequest, Channel clientChannel) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.BAD_GATEWAY,
                Unpooled.wrappedBuffer("baohulu upstream error".getBytes(StandardCharsets.UTF_8)));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        HttpUtil.setContentLength(response, response.content().readableBytes());
        writeWithKeepAlive(clientRequest, clientChannel, response);
    }

    /**
     * 按 keep-alive 语义写出响应
     */
    private void writeWithKeepAlive(FullHttpRequest clientRequest, Channel clientChannel,
                                    FullHttpResponse response) {
        if (HttpUtil.isKeepAlive(clientRequest) && clientChannel.isActive()) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            clientChannel.writeAndFlush(response);
        } else {
            clientChannel.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * 读取 ByteBuf 全量字节（调用线程内完成，防异步访问已释放内存）
     */
    private byte[] readContentBytes(ByteBuf content) {
        int length = content.readableBytes();
        if (length == 0) {
            return new byte[0];
        }
        byte[] bytes = new byte[length];
        content.getBytes(content.readerIndex(), bytes);
        return bytes;
    }

    /**
     * Netty Headers 转 Map（多值头逗号合并）
     */
    private Map<String, String> toHeaderMap(io.netty.handler.codec.http.HttpHeaders headers) {
        Map<String, String> map = new LinkedHashMap<>(16);
        for (Map.Entry<String, String> header : headers) {
            map.merge(header.getKey(), header.getValue(), (oldValue, value) -> oldValue + "," + value);
        }
        return map;
    }

    /**
     * 拼接目标 URL：https 走 MITM 域名 + 相对 URI；http 代理请求 relativeUri 已是绝对 URL
     */
    private String buildTargetUrl(String scheme, String hostWithPort, String relativeUri) {
        if (relativeUri.startsWith("http://") || relativeUri.startsWith("https://")) {
            return relativeUri;
        }
        return scheme + "://" + hostWithPort + relativeUri;
    }

    /**
     * 从 host:port 提取纯域名
     */
    private String stripPort(String hostWithPort) {
        int colonIndex = hostWithPort.lastIndexOf(':');
        if (colonIndex > 0 && hostWithPort.indexOf(':') == colonIndex
                && hostWithPort.substring(colonIndex + 1).chars().allMatch(Character::isDigit)) {
            return hostWithPort.substring(0, colonIndex);
        }
        return hostWithPort;
    }

    /**
     * 从 URL 提取域名（HeaderModifier 拦截点用）
     */
    private String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 判断 Content-Type 是否为文本类
     */
    private boolean isTextualContentType(String contentType) {
        if (contentType == null) {
            return true;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        return TEXTUAL_CONTENT_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * 从 Content-Type 解析字符集，默认 UTF-8
     */
    private Charset resolveCharset(String contentType) {
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.ROOT);
            int index = lower.indexOf("charset=");
            if (index >= 0) {
                String charsetName = lower.substring(index + "charset=".length()).trim();
                int semicolon = charsetName.indexOf(';');
                if (semicolon >= 0) {
                    charsetName = charsetName.substring(0, semicolon);
                }
                try {
                    return Charset.forName(charsetName);
                } catch (Exception e) {
                    log.warn("未知字符集, 使用 UTF-8 兜底, charset={}", charsetName);
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
