package com.baohulu.server;

import java.util.function.Supplier;

import com.baohulu.cert.DynamicCertManager;
import com.baohulu.proxy.ProxyFrontendHandler;
import com.baohulu.proxy.UpstreamForwarder;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;

/**
 * 代理服务 pipeline 初始化器
 * 单端口双角色入口：初始仅挂 HTTP 解码与前端分流器，
 * 由分流器按流量类型（CONNECT/绝对 URI/面板直连）动态重组管线
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public class ProxyServerInitializer extends ChannelInitializer<SocketChannel> {

    private final DynamicCertManager dynamicCertManager;

    private final UpstreamForwarder upstreamForwarder;

    /** 面板 handler 工厂（每连接一个） */
    private final Supplier<ChannelHandler> panelHandlerSupplier;

    public ProxyServerInitializer(DynamicCertManager dynamicCertManager, UpstreamForwarder upstreamForwarder,
                                  Supplier<ChannelHandler> panelHandlerSupplier) {
        this.dynamicCertManager = dynamicCertManager;
        this.upstreamForwarder = upstreamForwarder;
        this.panelHandlerSupplier = panelHandlerSupplier;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        // 初始只挂 HTTP 解码 + 分流器：不挂聚合器，保证 CONNECT 能被分流器优先识别
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new ProxyFrontendHandler(dynamicCertManager, upstreamForwarder,
                panelHandlerSupplier));
    }
}
