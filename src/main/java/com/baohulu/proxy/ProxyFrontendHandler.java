package com.baohulu.proxy;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baohulu.cert.DynamicCertManager;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;

/**
 * 前端流量分流器（单端口双角色的入口）
 * 三类流量的路由：
 * 1. CONNECT 请求 → 回 200，挂 SNI+动态证书（MITM 解密），转入明文代理管线
 * 2. 绝对 URI 请求（http://...）→ 普通 HTTP 代理，转入明文代理管线
 * 3. 相对 URI 请求 → 面板直连流量（静态资源/REST/WS），转入面板管线
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public class ProxyFrontendHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ProxyFrontendHandler.class);

    /** 明文请求聚合上限：8MB（覆盖小程序图片上传等大 body） */
    private static final int AGGREGATOR_MAX_BYTES = 8 * 1024 * 1024;

    /** HTTPS 默认端口 */
    private static final int DEFAULT_HTTPS_PORT = 443;

    private final DynamicCertManager dynamicCertManager;

    private final UpstreamForwarder upstreamForwarder;

    /** 面板 handler 工厂：每连接创建一个（Task 6 接入真实面板实现） */
    private final Supplier<ChannelHandler> panelHandlerSupplier;

    public ProxyFrontendHandler(DynamicCertManager dynamicCertManager, UpstreamForwarder upstreamForwarder,
                                Supplier<ChannelHandler> panelHandlerSupplier) {
        this.dynamicCertManager = dynamicCertManager;
        this.upstreamForwarder = upstreamForwarder;
        this.panelHandlerSupplier = panelHandlerSupplier;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof HttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (HttpMethod.CONNECT.equals(request.method())) {
            handleConnect(ctx, request);
            ReferenceCountUtil.release(msg);
            return;
        }
        if (request.uri().startsWith("http://") || request.uri().startsWith("https://")) {
            // 普通 HTTP 代理：重组管线后重放当前请求
            switchPipeline(ctx, request, new HttpObjectAggregator(AGGREGATOR_MAX_BYTES),
                    new HttpMitmHandler(upstreamForwarder));
            return;
        }
        // 相对 URI：面板直连流量
        switchPipeline(ctx, request, new HttpObjectAggregator(AGGREGATOR_MAX_BYTES),
                panelHandlerSupplier.get());
    }

    /**
     * CONNECT 隧道处理：回应建立成功，挂 SNI 动态证书进入 MITM 模式
     */
    private void handleConnect(ChannelHandlerContext ctx, HttpRequest request) {
        String hostAndPort = request.uri();
        // 规范化：无端口默认 443
        String hostWithPort = hostAndPort.contains(":") ? hostAndPort : hostAndPort + ":" + DEFAULT_HTTPS_PORT;
        String host = hostWithPort.substring(0, hostWithPort.lastIndexOf(':'));
        ctx.channel().attr(HttpMitmHandler.TARGET_HOST_KEY).set(hostWithPort);
        ctx.channel().attr(HttpMitmHandler.TARGET_SCHEME_KEY).set("https");
        // 回应隧道建立成功，后续字节流为 TLS 握手
        ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                new HttpResponseStatus(200, "Connection Established")));
        ChannelPipeline pipeline = ctx.pipeline();
        // SNI 处理器插到 HttpServerCodec 之前：先解密再解码
        pipeline.addFirst(new MitmSniHandler(dynamicCertManager, host));
        pipeline.addLast(new HttpObjectAggregator(AGGREGATOR_MAX_BYTES));
        pipeline.addLast(new HttpMitmHandler(upstreamForwarder));
        pipeline.remove(this);
    }

    /**
     * 重组管线（追加聚合器与目标 handler）并重放当前请求
     * 必须先移除自身再重放，避免消息二次进入本分流器；
     * 重放从 pipeline 头部传播，HttpServerCodec 对非 ByteBuf 消息直接透传，可安全到达新 handler
     */
    private void switchPipeline(ChannelHandlerContext ctx, HttpRequest request, ChannelHandler... handlers) {
        ChannelPipeline pipeline = ctx.pipeline();
        for (ChannelHandler handler : handlers) {
            pipeline.addLast(handler);
        }
        pipeline.remove(this);
        pipeline.fireChannelRead(request);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("前端连接处理异常, remote={}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
