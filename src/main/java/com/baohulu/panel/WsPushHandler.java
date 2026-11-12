package com.baohulu.panel;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baohulu.traffic.TrafficEntry;
import com.baohulu.traffic.TrafficStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

/**
 * 面板 WebSocket 推送处理器
 * 订阅流量存储的新增事件，将精简摘要实时推送到面板；
 * handlerAdded 时绑定连接（构造期拿不到 Channel），连接断开时取消订阅
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public class WsPushHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(WsPushHandler.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TrafficStore trafficStore;

    /** handlerAdded 时绑定的连接（推送出口） */
    private volatile Channel channel;

    /** 本连接的流量事件监听器（断开时取消订阅） */
    private final Consumer<TrafficEntry> listener = entry -> {
        Channel boundChannel = this.channel;
        if (boundChannel == null || !boundChannel.isActive()) {
            return;
        }
        String json = toSummaryJson(entry);
        if (json != null) {
            // write 线程安全：Netty 自动切换到连接的 eventLoop 执行
            boundChannel.writeAndFlush(new TextWebSocketFrame(json));
        }
    };

    public WsPushHandler(TrafficStore trafficStore) {
        this.trafficStore = trafficStore;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        this.channel = ctx.channel();
        trafficStore.addListener(listener);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        trafficStore.removeListener(listener);
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof CloseWebSocketFrame) {
            ctx.close();
            return;
        }
        if (frame instanceof PingWebSocketFrame ping) {
            ctx.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
            return;
        }
        // 面板仅单向接收推送，其余文本帧（如心跳）忽略
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("面板 WS 连接异常, remote={}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }

    /**
     * 序列化流量摘要，失败仅告警不中断推送链路
     */
    private String toSummaryJson(TrafficEntry entry) {
        try {
            return OBJECT_MAPPER.writeValueAsString(PanelRouteHandler.toSummary(entry));
        } catch (Exception e) {
            log.warn("流量摘要序列化失败, id={}", entry.getId(), e);
            return null;
        }
    }
}
