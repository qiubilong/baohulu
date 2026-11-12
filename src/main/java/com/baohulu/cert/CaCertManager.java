package com.baohulu.cert;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CA 根证书管理器
 * 首次运行生成自签 CA（RSA 2048，10 年有效期），以 PEM 持久化到数据目录，之后启动加载复用；
 * 根证书需安装到系统「受信任的根证书颁发机构」后，动态签发的域名证书才会被微信小程序信任
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public class CaCertManager {

    private static final Logger log = LoggerFactory.getLogger(CaCertManager.class);

    /** CA 主题名 */
    private static final X500Name CA_SUBJECT = new X500Name("CN=baohulu CA,O=baohulu,C=CN");

    /** CA 有效期：10 年 */
    private static final Duration CA_VALIDITY = Duration.ofDays(3650);

    /** 签名算法 */
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    /** RSA 密钥长度 */
    private static final int RSA_KEY_SIZE = 2048;

    /** CA 私钥文件名 */
    private static final String CA_KEY_FILE = "ca-key.pem";

    /** CA 证书文件名 */
    private static final String CA_CERT_FILE = "ca-cert.pem";

    private final Path caDir;

    private PrivateKey caPrivateKey;

    private X509Certificate caCertificate;

    public CaCertManager(Path caDir) {
        this.caDir = caDir;
        loadOrCreate();
    }

    public PrivateKey getCaPrivateKey() {
        return caPrivateKey;
    }

    public X509Certificate getCaCertificate() {
        return caCertificate;
    }

    /**
     * 导出 CA 证书 PEM 文本（面板下载安装用）
     *
     * @return PEM 格式证书文本
     */
    public String getCaCertificatePem() {
        StringWriter stringWriter = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(stringWriter)) {
            pemWriter.writeObject(caCertificate);
        } catch (IOException e) {
            throw new IllegalStateException("导出 CA 证书 PEM 失败", e);
        }
        return stringWriter.toString();
    }

    /**
     * 已存在则加载，否则生成新 CA 并持久化
     */
    private void loadOrCreate() {
        Path keyPath = caDir.resolve(CA_KEY_FILE);
        Path certPath = caDir.resolve(CA_CERT_FILE);
        if (Files.exists(keyPath) && Files.exists(certPath)) {
            loadFromFiles(keyPath, certPath);
        } else {
            createAndSave(keyPath, certPath);
        }
    }

    /**
     * 从 PEM 文件加载 CA 私钥与证书
     */
    private void loadFromFiles(Path keyPath, Path certPath) {
        try {
            JcaPEMKeyConverter keyConverter = new JcaPEMKeyConverter();
            try (Reader keyReader = Files.newBufferedReader(keyPath);
                 PEMParser pemParser = new PEMParser(keyReader)) {
                Object keyObject = pemParser.readObject();
                if (!(keyObject instanceof PEMKeyPair keyPair)) {
                    throw new IllegalStateException("CA 私钥文件格式不合法: " + keyPath);
                }
                caPrivateKey = keyConverter.getPrivateKey(keyPair.getPrivateKeyInfo());
            }
            try (Reader certReader = Files.newBufferedReader(certPath);
                 PEMParser pemParser = new PEMParser(certReader)) {
                Object certObject = pemParser.readObject();
                if (!(certObject instanceof X509CertificateHolder certHolder)) {
                    throw new IllegalStateException("CA 证书文件格式不合法: " + certPath);
                }
                caCertificate = new JcaX509CertificateConverter().getCertificate(certHolder);
            }
            log.info("已加载 CA 证书, 目录={}, 主题={}", caDir, caCertificate.getSubjectX500Principal().getName());
        } catch (IOException | CertificateException e) {
            throw new IllegalStateException("加载 CA 证书失败: " + caDir, e);
        }
    }

    /**
     * 生成自签 CA 并以 PEM 持久化
     */
    private void createAndSave(Path keyPath, Path certPath) {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(RSA_KEY_SIZE, new SecureRandom());
            KeyPair caKeyPair = keyPairGenerator.generateKeyPair();

            Instant now = Instant.now();
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    CA_SUBJECT,
                    new BigInteger(63, new SecureRandom()),
                    Date.from(now.minus(Duration.ofDays(1))),
                    Date.from(now.plus(CA_VALIDITY)),
                    CA_SUBJECT,
                    caKeyPair.getPublic());
            // CA 基本约束与密钥用途：仅用于签发证书
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            JcaX509ExtensionUtils extensionUtils = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                    extensionUtils.createSubjectKeyIdentifier(caKeyPair.getPublic()));
            builder.addExtension(Extension.authorityKeyIdentifier, false,
                    extensionUtils.createAuthorityKeyIdentifier(caKeyPair.getPublic()));

            caCertificate = new JcaX509CertificateConverter().getCertificate(builder.build(
                    new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(caKeyPair.getPrivate())));
            caPrivateKey = caKeyPair.getPrivate();

            Files.createDirectories(caDir);
            try (Writer keyWriter = Files.newBufferedWriter(keyPath);
                 JcaPEMWriter pemWriter = new JcaPEMWriter(keyWriter)) {
                pemWriter.writeObject(caKeyPair.getPrivate());
            }
            try (Writer certWriter = Files.newBufferedWriter(certPath);
                 JcaPEMWriter pemWriter = new JcaPEMWriter(certWriter)) {
                pemWriter.writeObject(caCertificate);
            }
            log.info("已生成新 CA 证书并持久化, 目录={}, 请安装 {} 到系统受信任存储", caDir, certPath.getFileName());
        } catch (NoSuchAlgorithmException | IOException | OperatorCreationException
                 | CertificateException e) {
            throw new IllegalStateException("生成 CA 证书失败: " + caDir, e);
        }
    }
}
