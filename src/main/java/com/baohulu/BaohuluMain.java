package com.baohulu;

import java.net.InetSocketAddress;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baohulu.cert.CaCertManager;
import com.baohulu.cert.DynamicCertManager;
import com.baohulu.config.AppConfig;
import com.baohulu.proxy.HeaderModifier;
import com.baohulu.proxy.UpstreamForwarder;
import com.baohulu.panel.PanelRouteHandler;
import com.baohulu.server.ProxyServerInitializer;
import com.baohulu.sysproxy.WinSystemProxy;
import com.baohulu.traffic.TrafficStore;

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
        // 组件装配：流量存储 / CA 证书 / 动态签发 / 上游转发
        TrafficStore trafficStore = new TrafficStore(config.bufferCapacity());
        CaCertManager caCertManager = new CaCertManager(
                Path.of(System.getProperty("user.home"), ".baohulu", "ca"));
        DynamicCertManager dynamicCertManager = new DynamicCertManager(caCertManager);
        UpstreamForwarder upstreamForwarder = new UpstreamForwarder(
                trafficStore, HeaderModifier.NOOP, config.bodyMaxKb());
        // 面板路由：静态资源 + REST + WebSocket 推送
        ProxyServerInitializer initializer = new ProxyServerInitializer(
                dynamicCertManager, upstreamForwarder,
                () -> new PanelRouteHandler(trafficStore, caCertManager, config.port()));

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(initializer);

            Channel channel = bootstrap.bind(new InetSocketAddress("127.0.0.1", config.port())).sync().channel();
            log.info("baohulu 已启动, 监听=127.0.0.1:{}, 流量缓冲={} 条, 面板=http://127.0.0.1:{}/",
                    config.port(), config.bufferCapacity(), config.port());

            // 端口就绪后接管系统代理（可 -Dbaohulu.auto.proxy=false 关闭）
            WinSystemProxy systemProxy = null;
            if (config.autoProxy()) {
                systemProxy = new WinSystemProxy();
                if (systemProxy.isResiduePointingTo(config.port())) {
                    log.warn("检测到系统代理残留指向本工具(上次可能异常退出), 退出时将一并还原");
                }
                if (systemProxy.enable(config.port())) {
                    log.info("系统代理已指向 127.0.0.1:{}, 重启微信后生效; 工具退出时自动还原", config.port());
                } else {
                    log.error("系统代理设置失败, 请手动将系统代理指向 127.0.0.1:{}", config.port());
                }
            }

            final WinSystemProxy proxyToRestore = systemProxy;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                // 优先还原系统代理, 避免后续清理阻塞导致代理长时间悬挂
                if (proxyToRestore != null) {
                    proxyToRestore.restore();
                }
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
