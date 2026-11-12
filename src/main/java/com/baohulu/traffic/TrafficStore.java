package com.baohulu.traffic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 流量内存环形存储
 * 环形缓冲保留最近 N 条流量记录，超出容量淘汰最旧记录；
 * 支持按域名过滤分页查询、id 详情查询与新增事件订阅（面板 WebSocket 推送使用）
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public class TrafficStore {

    private static final Logger log = LoggerFactory.getLogger(TrafficStore.class);

    /** 默认分页大小 */
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final int capacity;

    /** 按插入顺序保存流量，尾部为最新 */
    private final ArrayDeque<TrafficEntry> deque = new ArrayDeque<>();

    /** id -> 流量记录索引，用于详情查询 */
    private final Map<Long, TrafficEntry> indexMap = new ConcurrentHashMap<>();

    /** id 自增器 */
    private final AtomicLong idGenerator = new AtomicLong();

    /** 新流量事件监听器 */
    private final CopyOnWriteArrayList<Consumer<TrafficEntry>> listeners = new CopyOnWriteArrayList<>();

    public TrafficStore(int capacity) {
        this.capacity = capacity;
    }

    /**
     * 新增流量记录（自动分配 id），超容量淘汰最旧记录，并通知监听器
     *
     * @param entry 流量记录
     * @return 带自增 id 的记录
     */
    public TrafficEntry addTraffic(TrafficEntry entry) {
        entry.setId(idGenerator.incrementAndGet());
        synchronized (deque) {
            deque.addLast(entry);
            indexMap.put(entry.getId(), entry);
            while (deque.size() > capacity) {
                TrafficEntry evicted = deque.pollFirst();
                if (evicted != null) {
                    indexMap.remove(evicted.getId());
                }
            }
        }
        notifyListeners(entry);
        return entry;
    }

    /**
     * 按 id 查询单条流量
     *
     * @param id 流量 id
     * @return 流量记录，不存在返回 null
     */
    public TrafficEntry getTrafficById(long id) {
        return indexMap.get(id);
    }

    /**
     * 分页查询流量列表（最新在前）
     *
     * @param domain 域名过滤关键字，空则不过滤（忽略大小写、包含匹配）
     * @param page 页码，从 1 开始
     * @return 当前页流量列表
     */
    public List<TrafficEntry> queryTrafficList(String domain, int page) {
        List<TrafficEntry> snapshot = querySnapshotNewestFirst(domain);
        int fromIndex = Math.max(page - 1, 0) * DEFAULT_PAGE_SIZE;
        if (fromIndex >= snapshot.size()) {
            return Collections.emptyList();
        }
        int toIndex = Math.min(fromIndex + DEFAULT_PAGE_SIZE, snapshot.size());
        return snapshot.subList(fromIndex, toIndex);
    }

    /**
     * 查询符合条件的流量总数（面板分页用）
     *
     * @param domain 域名过滤关键字，空则统计全部
     * @return 流量条数
     */
    public int queryTrafficCount(String domain) {
        return querySnapshotNewestFirst(domain).size();
    }

    /**
     * 清空全部流量记录
     */
    public void clearTraffic() {
        synchronized (deque) {
            deque.clear();
            indexMap.clear();
        }
    }

    /**
     * 订阅新流量事件
     */
    public void addListener(Consumer<TrafficEntry> listener) {
        listeners.add(listener);
    }

    /**
     * 取消订阅新流量事件
     */
    public void removeListener(Consumer<TrafficEntry> listener) {
        listeners.remove(listener);
    }

    /**
     * 获取过滤后的快照（最新在前）
     */
    private List<TrafficEntry> querySnapshotNewestFirst(String domain) {
        List<TrafficEntry> snapshot;
        synchronized (deque) {
            snapshot = new ArrayList<>(deque.size());
            snapshot.addAll(deque);
        }
        Collections.reverse(snapshot);
        if (domain != null && !domain.isBlank()) {
            String keyword = domain.toLowerCase();
            snapshot.removeIf(e -> e.getHost() == null || !e.getHost().toLowerCase().contains(keyword));
        }
        return snapshot;
    }

    /**
     * 通知所有监听器，单个监听器异常不影响其他
     */
    private void notifyListeners(TrafficEntry entry) {
        for (Consumer<TrafficEntry> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (Exception e) {
                log.error("流量事件通知失败, id={}, url={}", entry.getId(), entry.getUrl(), e);
            }
        }
    }
}
