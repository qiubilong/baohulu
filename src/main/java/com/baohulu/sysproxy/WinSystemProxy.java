package com.baohulu.sysproxy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows 系统代理管理
 * 通过注册表 HKCU\...\Internet Settings 写入/还原系统代理（WinINet 层），
 * 设置后需重启微信等应用读取新代理；进程退出时自动还原，防止残留
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public class WinSystemProxy {

    private static final Logger log = LoggerFactory.getLogger(WinSystemProxy.class);

    /** 系统代理注册表键（当前用户级） */
    private static final String REG_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";

    /** reg query 输出的值行解析：形如 "    ProxyEnable    REG_DWORD    0x1" */
    private static final Pattern VALUE_LINE_PATTERN =
            Pattern.compile("\\s*(\\S+)\\s+REG_(DWORD|SZ)\\s+(\\S+)");

    /** reg 命令超时（毫秒） */
    private static final long REG_TIMEOUT_MS = 5000;

    /** 原始 ProxyEnable 值（null 表示原本不存在该键值） */
    private String originalEnable;

    /** 原始 ProxyServer 值（null 表示原本不存在该键值） */
    private String originalServer;

    /** 是否已执行过还原，保证 restore 幂等 */
    private boolean restored = false;

    /**
     * 检测系统代理是否残留指向本工具端口
     * 用于启动自检：上次异常退出未还原时给出提示
     *
     * @param port 代理端口
     * @return true 表示系统代理正指向 127.0.0.1:port
     */
    public boolean isResiduePointingTo(int port) {
        String enable = queryValue("ProxyEnable");
        String server = queryValue("ProxyServer");
        return "0x1".equalsIgnoreCase(enable)
                && server != null
                && server.contains("127.0.0.1:" + port);
    }

    /**
     * 启用系统代理并指向本工具
     * 首次调用时记录原值供还原使用，重复调用不覆盖已记录的原值
     *
     * @param port 代理端口
     * @return true 表示写入成功
     */
    public boolean enable(int port) {
        if (originalEnable == null && originalServer == null) {
            originalEnable = queryValue("ProxyEnable");
            originalServer = queryValue("ProxyServer");
        }
        boolean enableOk = writeDword("ProxyEnable", 1);
        boolean serverOk = writeString("ProxyServer", "127.0.0.1:" + port);
        if (!enableOk || !serverOk) {
            log.error("系统代理写入失败: ProxyEnable={}, ProxyServer={}", enableOk, serverOk);
            return false;
        }
        return true;
    }

    /**
     * 还原系统代理为启用前状态
     * 原值存在则写回原值，原本不存在则删除键值；可安全重复调用
     */
    public void restore() {
        if (restored) {
            return;
        }
        restored = true;
        if (originalEnable == null && originalServer == null) {
            // 从未执行过 enable，无需还原
            return;
        }
        boolean ok = true;
        if (originalEnable == null) {
            ok &= deleteValue("ProxyEnable");
        } else {
            ok &= writeDword("ProxyEnable", "0x1".equalsIgnoreCase(originalEnable) ? 1 : 0);
        }
        if (originalServer == null) {
            ok &= deleteValue("ProxyServer");
        } else {
            ok &= writeString("ProxyServer", originalServer);
        }
        if (ok) {
            log.info("系统代理已还原为启用前状态");
        } else {
            log.error("系统代理还原失败, 请手动检查注册表: {}", REG_KEY);
        }
    }

    /**
     * 查询注册表键值
     *
     * @param valueName 键值名
     * @return 值内容（DWORD 形如 0x1），键值不存在或查询失败返回 null
     */
    private String queryValue(String valueName) {
        String output = executeReg(new String[] {"reg", "query", REG_KEY, "/v", valueName});
        if (output == null) {
            return null;
        }
        Matcher matcher = VALUE_LINE_PATTERN.matcher(output);
        while (matcher.find()) {
            if (valueName.equals(matcher.group(1))) {
                return matcher.group(3);
            }
        }
        return null;
    }

    /**
     * 写入 REG_DWORD 键值
     *
     * @param valueName 键值名
     * @param value 整数值
     * @return true 表示写入成功
     */
    private boolean writeDword(String valueName, int value) {
        return executeReg(new String[] {
                "reg", "add", REG_KEY, "/v", valueName, "/t", "REG_DWORD",
                "/d", String.valueOf(value), "/f"}) != null;
    }

    /**
     * 写入 REG_SZ 键值
     *
     * @param valueName 键值名
     * @param value 字符串值
     * @return true 表示写入成功
     */
    private boolean writeString(String valueName, String value) {
        return executeReg(new String[] {
                "reg", "add", REG_KEY, "/v", valueName, "/t", "REG_SZ",
                "/d", value, "/f"}) != null;
    }

    /**
     * 删除键值（键值不存在视为成功）
     *
     * @param valueName 键值名
     * @return true 表示删除成功或本就不存在
     */
    private boolean deleteValue(String valueName) {
        String output = executeReg(new String[] {"reg", "delete", REG_KEY, "/v", valueName, "/f"});
        if (output != null) {
            return true;
        }
        // 删除不存在的键值 reg 返回非 0，此时视为已达成目标
        return queryValue(valueName) == null;
    }

    /**
     * 执行 reg 命令并返回标准输出
     * reg 输出按 ISO-8859-1 解码保证 ASCII 内容不受本机 GBK 代码页影响
     *
     * @param command 完整命令
     * @return 标准输出内容，执行失败或超时返回 null
     */
    private String executeReg(String[] command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(REG_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("reg 命令超时: {}", String.join(" ", command));
                return null;
            }
            String output = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            if (process.exitValue() != 0) {
                // query 不存在的键值属预期情况，降级为 debug
                log.debug("reg 命令退出码 {}: {}", process.exitValue(), output.trim());
                return null;
            }
            return output;
        } catch (IOException e) {
            log.error("reg 命令执行失败: {}", String.join(" ", command), e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("reg 命令执行被中断: {}", String.join(" ", command), e);
            return null;
        }
    }
}
