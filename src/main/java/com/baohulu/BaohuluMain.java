package com.baohulu;

import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baohulu.config.AppConfig;
import com.baohulu.server.ProxyServerInitializer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * baohulu 启动入口
 * 启动单端口双角色 Netty 服务：HTTP(S) 代理 + 管理面板
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public class BaohuluMain {

    private static final Logger log = LoggerFactory.getLogger(BaohuluMain.class);

    public static void main(String[] args) {
        AppConfig config = AppConfig.loadFromSystemProperties();
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ProxyServerInitializer(config));

            Channel channel = bootstrap.bind(new InetSocketAddress("127.0.0.1", config.port())).sync().channel();
            log.info("baohulu 已启动, 监听=127.0.0.1:{}, 流量缓冲={} 条, 面板=http://127.0.0.1:{}/",
                    config.port(), config.bufferCapacity(), config.port());

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("baohulu 正在退出, 释放资源...");
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            }, "baohulu-shutdown"));

            channel.closeFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("baohulu 启动或等待关闭时被中断", e);
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
