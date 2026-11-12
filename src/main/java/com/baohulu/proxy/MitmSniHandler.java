package com.baohulu.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baohulu.cert.DynamicCertManager;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsyncMapping;
import io.netty.util.concurrent.Future;

/**
 * MITM 场景的 SNI 处理器
 * 从客户端 TLS ClientHello 提取 SNI 域名，动态签发对应证书构建服务端 SslContext；
 * 客户端不带 SNI 时回退到 CONNECT 声明的目标域名
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public class MitmSniHandler extends SniHandler {

    private static final Logger log = LoggerFactory.getLogger(MitmSniHandler.class);

    private final DynamicCertManager dynamicCertManager;

    /** SNI 缺失时的兜底域名（CONNECT 目标） */
    private final String fallbackHost;

    public MitmSniHandler(DynamicCertManager dynamicCertManager, String fallbackHost) {
        // 占位映射：lookup 已覆写为动态签发，该映射不会被触达
        super((AsyncMapping<String, SslContext>) (hostname, promise) -> {
            throw new UnsupportedOperationException("not reachable");
        });
        this.dynamicCertManager = dynamicCertManager;
        this.fallbackHost = fallbackHost;
    }

    @Override
    protected Future<SslContext> lookup(ChannelHandlerContext ctx, String hostname) throws Exception {
        String host = (hostname != null && !hostname.isEmpty()) ? hostname : fallbackHost;
        try {
            SslContext sslContext = dynamicCertManager.getOrCreateSslContext(host);
            return ctx.channel().eventLoop().newSucceededFuture(sslContext);
        } catch (Exception e) {
            log.error("域名证书签发失败, host={}, 关闭连接", host, e);
            throw e;
        }
    }
}
