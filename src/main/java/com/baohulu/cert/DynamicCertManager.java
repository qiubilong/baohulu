package com.baohulu.cert;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

/**
 * 域名动态证书管理器
 * 按目标域名用 CA 签发短有效期叶子证书（SAN 含域名）并构建 Netty 服务端 SslContext，
 * LRU 缓存避免重复签发；用于 CONNECT 隧道的 MITM 解密
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public class DynamicCertManager {

    private static final Logger log = LoggerFactory.getLogger(DynamicCertManager.class);

    /** 域名 SslContext 缓存上限 */
    private static final int CACHE_MAX_SIZE = 256;

    /** 叶子证书有效期：7 天 */
    private static final Duration LEAF_VALIDITY = Duration.ofDays(7);

    /** 签名算法（与 CA 一致） */
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    /** RSA 密钥长度 */
    private static final int RSA_KEY_SIZE = 2048;

    private final CaCertManager caCertManager;

    /** 域名 -> SslContext 的 LRU 缓存 */
    private final Map<String, SslContext> sslContextCache =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SslContext> eldest) {
                    return size() > CACHE_MAX_SIZE;
                }
            });

    public DynamicCertManager(CaCertManager caCertManager) {
        this.caCertManager = caCertManager;
    }

    /**
     * 获取（缓存命中）或签发指定域名的服务端 SslContext
     *
     * @param host 目标域名（或 IP 字符串）
     * @return 服务端 SslContext
     */
    public SslContext getOrCreateSslContext(String host) {
        SslContext cached = sslContextCache.get(host);
        if (cached != null) {
            return cached;
        }
        SslContext sslContext = buildSslContext(host);
        sslContextCache.put(host, sslContext);
        return sslContext;
    }

    /**
     * 用 CA 为域名签发叶子证书并构建服务端 SslContext
     */
    private SslContext buildSslContext(String host) {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(RSA_KEY_SIZE, new SecureRandom());
            KeyPair serverKeyPair = keyPairGenerator.generateKeyPair();

            Instant now = Instant.now();
            X500Name subject = new X500Name("CN=" + host);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    caCertManager.getCaCertificate(),
                    new BigInteger(63, new SecureRandom()),
                    Date.from(now.minus(Duration.ofHours(1))),
                    Date.from(now.plus(LEAF_VALIDITY)),
                    subject,
                    serverKeyPair.getPublic());
            // SAN：域名或 IP；客户端（微信 Chromium 内核）会校验 SAN 与请求主机一致
            GeneralName generalName = isIpAddress(host)
                    ? new GeneralName(GeneralName.iPAddress, host)
                    : new GeneralName(GeneralName.dNSName, host);
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(generalName));
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            builder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));

            X509Certificate leafCertificate = new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                            .build(caCertManager.getCaPrivateKey())));

            return SslContextBuilder.forServer(serverKeyPair.getPrivate(),
                    leafCertificate, caCertManager.getCaCertificate()).build();
        } catch (Exception e) {
            // 签发失败不可吞掉：无证书则无法完成 MITM 握手，必须快速失败
            throw new IllegalStateException("签发域名证书失败, host=" + host, e);
        }
    }

    /**
     * 判断 host 是否为 IP 地址（用于 SAN 类型选择）
     */
    private boolean isIpAddress(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!Character.isDigit(c) && c != '.' && c != ':') {
                return false;
            }
        }
        return true;
    }
}
