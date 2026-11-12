package com.baohulu.server;

import java.nio.charset.StandardCharsets;

import com.baohulu.config.AppConfig;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
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

/**
 * 代理服务 pipeline 初始化器
 * 骨架占位实现：对任意请求返回提示响应
 * 后续任务将替换为「面板流量分流 + HTTP(S) 代理转发」双角色路由
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public class ProxyServerInitializer extends ChannelInitializer<SocketChannel> {

    private final AppConfig config;

    public ProxyServerInitializer(AppConfig config) {
        this.config = config;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(1024 * 1024));
        pipeline.addLast(new PlaceholderHandler());
    }

    /**
     * 占位处理器：返回启动提示，等待真实分流逻辑替换
     */
    private static class PlaceholderHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String tip = "baohulu proxy is starting, panel is under construction.";
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK, Unpooled.copiedBuffer(tip, StandardCharsets.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
            HttpUtil.setContentLength(response, response.content().readableBytes());
            if (HttpUtil.isKeepAlive(request)) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                ctx.writeAndFlush(response);
            } else {
                ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
