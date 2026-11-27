package com.countmaske.orderagent.repository;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存库存仓储（不引入 Redis/MySQL）。
 *
 * 每个商品两项数据：
 * - stock   ：剩余库存，AtomicInteger + CAS 扣减，避免超卖
 * - version ：每次成功扣减/恢复自增，供上层观察并发流转（乐观锁语义）
 *
 * 用 ConcurrentHashMap 而非 HashMap，是因为工具可能被并发调用。
 */
@Repository
public class InventoryRepository {

    private final Map<String, AtomicInteger> stock = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> version = new ConcurrentHashMap<>();

    public InventoryRepository() {
        // 初始化 3 个 mock 商品的库存，与 SearchTool 的商品表保持一致
        stock.put("1", new AtomicInteger(100));
        stock.put("2", new AtomicInteger(100));
        stock.put("3", new AtomicInteger(100));
        version.put("1", new AtomicLong(0));
        version.put("2", new AtomicLong(0));
        version.put("3", new AtomicLong(0));
    }

    /**
     * CAS 扣减库存：库存不足返回 false，不做部分扣减。
     */
    public boolean deduct(String productId, int quantity) {
        AtomicInteger current = stock.get(productId);
        if (current == null) {
            return false;
        }
        while (true) {
            int available = current.get();
            if (available < quantity) {
                return false;
            }
            if (current.compareAndSet(available, available - quantity)) {
                version.computeIfAbsent(productId, k -> new AtomicLong(0)).incrementAndGet();
                return true;
            }
        }
    }

    /**
     * 恢复库存（取消订单时调用），同样用 CAS。
     */
    public void restore(String productId, int quantity) {
        AtomicInteger current = stock.get(productId);
        if (current == null) {
            return;
        }
        while (true) {
            int available = current.get();
            if (current.compareAndSet(available, available + quantity)) {
                version.computeIfAbsent(productId, k -> new AtomicLong(0)).incrementAndGet();
                return;
            }
        }
    }

    public int getStock(String productId) {
        AtomicInteger value = stock.get(productId);
        return value == null ? 0 : value.get();
    }

    public long getVersion(String productId) {
        AtomicLong value = version.get(productId);
        return value == null ? 0L : value.get();
    }
}
