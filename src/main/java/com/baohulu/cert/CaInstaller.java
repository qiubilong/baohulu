package com.baohulu.cert;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CA 证书一键安装器（Windows）
 * 通过 certutil 写入当前用户「受信任的根证书颁发机构」（免管理员），并用 SunMSCAPI 读回指纹复验。
 * 存在意义：Windows 证书导入向导的「自动选择存储」常把自签 CA 误装入「中级证书颁发机构」，
 * 中级证书不构成信任锚，导致用户"装了证书还是不行"；一键安装显式指明 Root 存储规避该坑
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public final class CaInstaller {

    private static final Logger log = LoggerFactory.getLogger(CaInstaller.class);

    /** certutil 执行超时：期间系统可能弹出证书安全确认框，留足用户反应时间 */
    private static final Duration INSTALL_TIMEOUT = Duration.ofSeconds(20);

    private CaInstaller() {
    }

    /**
     * 安装结果（面板直接透传给前端展示）
     *
     * @param ok 是否安装成功（以指纹复验为准）
     * @param message 结果说明
     */
    public record InstallResult(boolean ok, String message) {
    }

    /**
     * 将 CA 证书安装到当前用户「受信任的根证书颁发机构」并复验
     *
     * @param caCertificate 待安装的 CA 证书（复验指纹用）
     * @param pemPath CA 证书 PEM 文件路径（certutil 入参）
     * @return 安装结果
     */
    public static InstallResult installToUserRoot(X509Certificate caCertificate, Path pemPath) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return new InstallResult(false, "仅支持 Windows 系统");
        }
        try {
            Process process = new ProcessBuilder(
                            "certutil", "-addstore", "-user", "Root", pemPath.toString())
                    .redirectErrorStream(true)
                    .start();
            // 期间系统可能弹证书安全确认框，等待时留足超时；输出仅几行不会撑爆管道缓冲
            if (!process.waitFor(INSTALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("certutil 安装超时(可能确认框未点), pem={}", pemPath);
                return new InstallResult(false, "安装超时：若屏幕出现安全确认框请点「是」后重试");
            }
            // 输出为本地代码页编码，仅记日志不做展示，读取防止字节歧义
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            if (process.exitValue() != 0) {
                log.warn("certutil 安装失败, exitCode={}, output={}", process.exitValue(), output);
                return new InstallResult(false, "certutil 安装失败, 退出码=" + process.exitValue());
            }
            if (verifyInstalled(caCertificate)) {
                log.info("CA 证书已安装到当前用户受信任根存储并复验通过");
                return new InstallResult(true, "已安装到「受信任的根证书颁发机构」, 重启浏览器/微信后生效");
            }
            log.warn("certutil 成功但指纹复验未命中, pem={}", pemPath);
            return new InstallResult(false, "安装命令已执行但复验未通过, 请手动执行: certutil -addstore -user Root \"" + pemPath + "\"");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new InstallResult(false, "安装被中断");
        } catch (Exception e) {
            log.error("CA 一键安装异常, pem={}", pemPath, e);
            return new InstallResult(false, "安装异常: " + e.getMessage());
        }
    }

    /**
     * 从 Windows 当前用户 Root 存储读回证书并比对指纹（SHA-256）
     *
     * @param caCertificate 期望存在的 CA 证书
     * @return true 表示存储中已存在同指纹证书
     */
    private static boolean verifyInstalled(X509Certificate caCertificate) {
        try {
            KeyStore store = KeyStore.getInstance("Windows-ROOT");
            store.load(null, null);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] expected = digest.digest(caCertificate.getEncoded());
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                Certificate stored = store.getCertificate(aliases.nextElement());
                if (stored != null && MessageDigest.isEqual(expected, digest.digest(stored.getEncoded()))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("复验 CA 安装状态失败", e);
            return false;
        }
    }
}
