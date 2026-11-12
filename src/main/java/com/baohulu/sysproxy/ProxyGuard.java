package com.baohulu.sysproxy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 系统代理还原哨兵
 * 工具接管系统代理时拉起一个独立隐藏控制台的批处理哨兵，轮询本工具进程存活状态；
 * 进程死亡（含任务管理器强杀、Windows Terminal 点 X 连坐等一切非正常退出）后，
 * 哨兵自动将注册表还原为接管前原值，防止"死代理挡路导致整机断网"。
 * 正常退出场景由 shutdown hook 先行还原，哨兵随后幂等执行一遍并自删，双保险。
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public final class ProxyGuard {

    private static final Logger log = LoggerFactory.getLogger(ProxyGuard.class);

    /** 哨兵批处理文件名（位于系统临时目录） */
    private static final String GUARD_BAT = "baohulu-proxy-restore.bat";

    private ProxyGuard() {
    }

    /**
     * 拉起还原哨兵
     *
     * @param toolPid 本工具进程 PID（哨兵轮询其存活状态）
     * @param originalEnable 接管前 ProxyEnable 原值（null 表示原本不存在，还原时删除键值）
     * @param originalServer 接管前 ProxyServer 原值（null 表示原本不存在，还原时删除键值）
     */
    public static void start(long toolPid, String originalEnable, String originalServer) {
        try {
            Path bat = writeGuardBat(toolPid, originalEnable, originalServer);
            // Start-Process 创建独立进程/独立控制台（Hidden），脱离本工具进程树与控制台会话：
            // Windows Terminal 点 X 会强杀整个控制台会话（shutdown hook 无机会执行），
            // 独立控制台的哨兵不受连坐，是强杀场景下唯一的还原保障
            new ProcessBuilder("powershell", "-NoProfile", "-Command",
                            "Start-Process cmd -WindowStyle Hidden -ArgumentList '/c','" + bat + "'")
                    .start();
            log.info("代理还原哨兵已启动, pid={}, 哨兵脚本={}", toolPid, bat);
        } catch (IOException e) {
            log.warn("代理还原哨兵启动失败, 异常退出时需手动还原系统代理", e);
        }
    }

    /**
     * 写哨兵批处理：轮询本工具 PID 直至消失，还原注册表后自删
     */
    private static Path writeGuardBat(long toolPid, String originalEnable, String originalServer) throws IOException {
        String regKey = "\"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\"";
        StringBuilder script = new StringBuilder(512);
        script.append("@echo off\r\n");
        script.append(":wait\r\n");
        // tasklist 过滤器精确匹配 PID：有输出行说明本工具仍存活，2 秒后再查
        script.append("tasklist /FI \"PID eq ").append(toolPid).append("\" | find \"").append(toolPid)
                .append("\" >nul 2>&1\r\n");
        script.append("if not errorlevel 1 (\r\n");
        script.append("  timeout /t 2 /nobreak >nul\r\n");
        script.append("  goto wait\r\n");
        script.append(")\r\n");
        // 本工具已退出（任何方式）：还原接管前原值
        script.append(originalEnable == null
                ? "reg delete " + regKey + " /v ProxyEnable /f >nul 2>&1\r\n"
                : "reg add " + regKey + " /v ProxyEnable /t REG_DWORD /d "
                        + ("0x1".equalsIgnoreCase(originalEnable) ? 1 : 0) + " /f >nul 2>&1\r\n");
        script.append(originalServer == null
                ? "reg delete " + regKey + " /v ProxyServer /f >nul 2>&1\r\n"
                : "reg add " + regKey + " /v ProxyServer /t REG_SZ /d \"" + originalServer + "\" /f >nul 2>&1\r\n");
        script.append("del \"%~f0\" >nul 2>&1\r\n");
        Path bat = Path.of(System.getProperty("java.io.tmpdir"), GUARD_BAT);
        Files.write(bat, script.toString().getBytes(StandardCharsets.US_ASCII));
        return bat;
    }
}
