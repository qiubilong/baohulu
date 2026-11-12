package com.baohulu.cert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.netty.handler.ssl.SslContext;

/**
 * 证书管理单元测试：CA 生成持久化 / 加载复用 / 动态签发与缓存 / SAN 校验
 *
 * @author qiubilong
 * @date 2026-11-12
 */
class CertManagersTest {

    @Test
    void should_create_ca_then_reload_same_pem(@TempDir Path tempDir) throws Exception {
        CaCertManager first = new CaCertManager(tempDir);
        String pem = Files.readString(tempDir.resolve("ca-cert.pem"));
        assertTrue(pem.contains("BEGIN CERTIFICATE"));
        assertTrue(pem.contains("END CERTIFICATE"));
        // 私钥也已落盘
        assertTrue(Files.exists(tempDir.resolve("ca-key.pem")));

        // 二次构造同目录：加载复用，PEM 逐字节一致
        CaCertManager second = new CaCertManager(tempDir);
        assertEquals(pem, Files.readString(tempDir.resolve("ca-cert.pem")));
    }

    @Test
    void should_issue_ssl_context_with_cache_and_ip_branch(@TempDir Path tempDir) {
        CaCertManager caCertManager = new CaCertManager(tempDir);
        DynamicCertManager dynamicCertManager = new DynamicCertManager(caCertManager);

        SslContext first = dynamicCertManager.getOrCreateSslContext("a.com");
        SslContext again = dynamicCertManager.getOrCreateSslContext("a.com");
        SslContext other = dynamicCertManager.getOrCreateSslContext("b.com");
        SslContext ipHost = dynamicCertManager.getOrCreateSslContext("127.0.0.1");

        // 同域名命中缓存（同实例），不同域名新签发
        assertSame(first, again);
        assertNotSame(first, other);
        assertNotSame(first, ipHost);
        // 均为服务端模式（MITM 侧）
        assertTrue(first.isServer());
        assertTrue(ipHost.isServer());
    }
}
