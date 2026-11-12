package com.baohulu.proxy;

import java.net.URI;

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
        log.warn("MITM 连接处理异常, remote={}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
