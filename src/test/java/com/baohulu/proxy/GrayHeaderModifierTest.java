package com.baohulu.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * GrayHeaderModifier 单元测试：开关状态与注入行为
 *
 * @author qiubilong
 * @date 2026-11-12
 */
class GrayHeaderModifierTest {

    private Map<String, String> headers(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void should_not_inject_when_disabled_by_default() {
        GrayHeaderModifier modifier = new GrayHeaderModifier();
        assertFalse(modifier.isEnabled(), "默认应关闭");

        Map<String, String> headers = headers("Host", "a.com");
        modifier.modifyHeaders("a.com", headers);

        assertTrue(headers.containsKey("Host"), "原有头不受影响");
        assertFalse(headers.keySet().stream().anyMatch(n -> n.equalsIgnoreCase("grayreleasetag")),
                "关闭时不应注入灰度头");
    }

    @Test
    void should_inject_gray_header_when_enabled() {
        GrayHeaderModifier modifier = new GrayHeaderModifier();
        assertTrue(modifier.toggle(), "切换后应开启");

        Map<String, String> headers = headers("Host", "a.com");
        modifier.modifyHeaders("a.com", headers);

        assertEquals("gray", headers.get("grayreleasetag"), "开启后应注入 grayreleasetag=gray");
    }

    @Test
    void should_not_override_existing_header_in_any_case() {
        GrayHeaderModifier modifier = new GrayHeaderModifier();
        modifier.toggle();

        Map<String, String> headers = headers("GrayReleaseTag", "custom-value");
        modifier.modifyHeaders("a.com", headers);

        assertEquals("custom-value", headers.get("GrayReleaseTag"),
                "客户端自带同款头（任意大小写）时不应覆盖");
        assertFalse(headers.containsKey("grayreleasetag"), "不应追加小写重复头");
    }

    @Test
    void should_stop_injecting_after_toggle_off() {
        GrayHeaderModifier modifier = new GrayHeaderModifier();
        modifier.toggle();

        Map<String, String> onHeaders = headers("Host", "a.com");
        modifier.modifyHeaders("a.com", onHeaders);
        assertEquals("gray", onHeaders.get("grayreleasetag"));

        assertFalse(modifier.toggle(), "再次切换应关闭");
        Map<String, String> offHeaders = headers("Host", "a.com");
        modifier.modifyHeaders("a.com", offHeaders);
        assertFalse(offHeaders.containsKey("grayreleasetag"), "关闭后应恢复不注入");
    }
}
