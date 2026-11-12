package com.baohulu.proxy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 灰度标记 Header 注入器（FR4 Header 注入的首个落地场景）
 * 开启后为所有上游请求注入 grayreleasetag: gray 请求头，用于将小程序流量路由到灰度环境；
 * 开关由面板 /api/gray 动态控制（默认关闭），客户端已自带同款头时不覆盖（尊重原始值）
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public class GrayHeaderModifier implements HeaderModifier {

    /** 灰度标记头名 */
    private static final String GRAY_HEADER_NAME = "grayreleasetag";

    /** 灰度标记头值 */
    private static final String GRAY_HEADER_VALUE = "gray";

    /** 注入开关（面板动态切换，默认关闭） */
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    /**
     * 切换注入开关（CAS 循环保证并发下始终切到目标态）
     *
     * @return 切换后的开关状态
     */
    public boolean toggle() {
        boolean previous;
        boolean next;
        do {
            previous = enabled.get();
            next = !previous;
        } while (!enabled.compareAndSet(previous, next));
        return next;
    }

    /**
     * 查询开关状态
     *
     * @return true 表示注入已开启
     */
    public boolean isEnabled() {
        return enabled.get();
    }

    @Override
    public void modifyHeaders(String host, Map<String, String> headers) {
        if (!enabled.get()) {
            return;
        }
        // 已自带同款头（任意大小写）时不覆盖，尊重客户端原始值
        boolean present = headers.keySet().stream()
                .anyMatch(name -> name.equalsIgnoreCase(GRAY_HEADER_NAME));
        if (!present) {
            headers.put(GRAY_HEADER_NAME, GRAY_HEADER_VALUE);
        }
    }
}
