package com.baohulu.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * TrafficStore 单元测试：自增 id / 环形淘汰 / 过滤分页 / 监听器 / 清空 / 容量防御
 *
 * @author qiubilong
 * @date 2026-11-12
 */
class TrafficStoreTest {

    /** 构造一条最小流量记录 */
    private TrafficEntry buildEntry(String host) {
        TrafficEntry entry = new TrafficEntry();
        entry.setHost(host);
        entry.setMethod("GET");
        entry.setUrl("http://" + host + "/");
        return entry;
    }

    @Test
    void should_assign_increment_id_and_list_newest_first() {
        TrafficStore store = new TrafficStore(100);
        store.addTraffic(buildEntry("a.com"));
        store.addTraffic(buildEntry("b.com"));
        store.addTraffic(buildEntry("c.com"));

        List<TrafficEntry> list = store.queryTrafficList(null, 1);
        assertEquals(3, list.size());
        // 最新在前：第一条是最后插入的
        assertEquals("c.com", list.get(0).getHost());
        assertEquals("a.com", list.get(2).getHost());
        assertTrue(list.get(0).getId() > list.get(1).getId()
                && list.get(1).getId() > list.get(2).getId());
    }

    @Test
    void should_evict_oldest_when_capacity_exceeded() {
        TrafficStore store = new TrafficStore(3);
        for (int i = 1; i <= 5; i++) {
            store.addTraffic(buildEntry("h" + i + ".com"));
        }
        List<TrafficEntry> list = store.queryTrafficList(null, 1);
        assertEquals(3, list.size());
        assertEquals("h5.com", list.get(0).getHost());
        // 被淘汰的最旧记录索引同步移除
        assertNull(store.getTrafficById(1));
        assertNull(store.getTrafficById(2));
        assertNotNull(store.getTrafficById(5));
        assertEquals(3, store.queryTrafficCount(null));
    }

    @Test
    void should_reject_non_positive_capacity() {
        assertThrows(IllegalArgumentException.class, () -> new TrafficStore(0));
        assertThrows(IllegalArgumentException.class, () -> new TrafficStore(-1));
    }

    @Test
    void should_filter_domain_case_insensitive_and_empty_page() {
        TrafficStore store = new TrafficStore(100);
        store.addTraffic(buildEntry("example.com"));
        store.addTraffic(buildEntry("api.github.com"));
        store.addTraffic(buildEntry("EXAMPLE.org"));

        // 忽略大小写、包含匹配
        List<TrafficEntry> hit = store.queryTrafficList("EXAMPLE", 1);
        assertEquals(2, hit.size());
        assertEquals(2, store.queryTrafficCount("example"));

        // 无命中
        assertTrue(store.queryTrafficList("nomatch", 1).isEmpty());

        // 分页越界返回空
        assertTrue(store.queryTrafficList(null, 99).isEmpty());
    }

    @Test
    void should_notify_listeners_and_isolate_exception() {
        TrafficStore store = new TrafficStore(10);
        AtomicInteger normalCount = new AtomicInteger();
        AtomicInteger brokenCount = new AtomicInteger();
        java.util.function.Consumer<TrafficEntry> broken = e -> {
            brokenCount.incrementAndGet();
            throw new IllegalStateException("listener boom");
        };
        java.util.function.Consumer<TrafficEntry> normal = e -> normalCount.incrementAndGet();
        store.addListener(broken);
        store.addListener(normal);

        store.addTraffic(buildEntry("a.com"));

        // 异常监听器不影响另一个监听器与主流程
        assertEquals(1, brokenCount.get());
        assertEquals(1, normalCount.get());
        assertEquals(1, store.queryTrafficCount(null));

        // 移除后不再通知
        store.removeListener(normal);
        store.removeListener(broken);
        store.addTraffic(buildEntry("b.com"));
        assertEquals(1, normalCount.get());
        assertEquals(1, brokenCount.get());
    }

    @Test
    void should_clear_all_traffic() {
        TrafficStore store = new TrafficStore(10);
        TrafficEntry first = store.addTraffic(buildEntry("a.com"));
        store.addTraffic(buildEntry("b.com"));
        store.clearTraffic();

        assertEquals(0, store.queryTrafficCount(null));
        assertTrue(store.queryTrafficList(null, 1).isEmpty());
        assertNull(store.getTrafficById(first.getId()));
    }
}
