package com.baohulu.proxy;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLHandshakeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.util.AttributeKey;

/**
 * MITM 明文请求处理器
 * 接收解密后的完整明文请求（HTTPS MITM）或代理转发的 HTTP 请求（绝对 URI），
 * 交给上游转发器处理
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public class HttpMitmHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpMitmHandler.class);

    /** 当前连接的目标 host（含端口，CONNECT 阶段或绝对 URI 解析时写入） */
    public static final AttributeKey<String> TARGET_HOST_KEY = AttributeKey.valueOf("baohulu.targetHost");

    /** 当前连接的目标协议（http/https） */
    public static final AttributeKey<String> TARGET_SCHEME_KEY = AttributeKey.valueOf("baohulu.targetScheme");

    /** 客户端不信任 CA 的人话提示是否已打印过（避免连接风暴刷屏，仅首次提示） */
    private static final AtomicBoolean CERT_TRUST_TIP_PRINTED = new AtomicBoolean(false);

    private final UpstreamForwarder upstreamForwarder;

    public HttpMitmHandler(UpstreamForwarder upstreamForwarder) {
        this.upstreamForwarder = upstreamForwarder;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String scheme = ctx.channel().attr(TARGET_SCHEME_KEY).get();
        String hostWithPort = ctx.channel().attr(TARGET_HOST_KEY).get();
        String uri = request.uri();
        // HTTP 代理（明文绝对 URI）：解析真实目标并缓存到连接属性
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            URI absoluteUri = URI.create(uri);
            int port = absoluteUri.getPort();
            hostWithPort = absoluteUri.getHost() + (port == -1 ? "" : ":" + port);
            scheme = absoluteUri.getScheme();
            ctx.channel().attr(TARGET_HOST_KEY).set(hostWithPort);
            ctx.channel().attr(TARGET_SCHEME_KEY).set(scheme);
        }
        if (hostWithPort == null || scheme == null) {
            log.warn("请求缺少目标信息, 关闭连接, uri={}", uri);
            ctx.close();
            return;
        }
        upstreamForwarder.forward(request, scheme, hostWithPort, uri, ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (isCertificateUnknownAlert(cause)) {
            // 系统代理接管后未装 CA 的典型场景：本机应用的 HTTPS 集中被拒，首次给人话提示，后续降级避免刷屏
            if (CERT_TRUST_TIP_PRINTED.compareAndSet(false, true)) {
                log.warn("客户端不信任 baohulu CA 证书: 请打开面板页面点击「一键安装CA」(安装到「受信任的根证书颁发机构」), "
                        + "安装前走系统代理的应用 HTTPS 将持续失败; 本提示仅打印一次, 后续同类告警降为 DEBUG");
            } else {
                log.debug("客户端不信任 CA, 握手被拒, remote={}", ctx.channel().remoteAddress());
            }
            ctx.close();
            return;
        }
        log.warn("MITM 连接处理异常, remote={}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }

    /**
     * 判断异常链中是否为客户端拒信证书的 certificate_unknown 告警
     *
     * @param cause 管线异常（Netty DecoderException 包裹 SSLHandshakeException）
     * @return true 表示客户端因不信任证书主动拒绝握手
     */
    private boolean isCertificateUnknownAlert(Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof SSLHandshakeException && current.getMessage() != null
                    && current.getMessage().contains("certificate_unknown")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
