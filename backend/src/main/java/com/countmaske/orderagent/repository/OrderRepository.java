package com.countmaske.orderagent.repository;

import com.countmaske.orderagent.domain.Order;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存订单仓储（本轮不引入 Redis/MySQL）。
 *
 * 两个索引：
 * - orders           : orderId -> Order，主存储
 * - idempotencyIndex : idempotencyKey -> orderId，幂等去重索引
 *
 * 幂等键唯一约束用 putIfAbsent 保证原子：同一个幂等键并发创建时，
 * 只有一个线程能把 (key -> orderId) 写进去，其余线程读回已存在的 orderId。
 */
@Repository
public class OrderRepository {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();

    /**
     * 原子抢占幂等键：成功写入返回 true；
     * 键已存在返回 false，并把 value 指向已存在的订单号（通过 findByIdempotencyKey 读回）。
     */
    public boolean claimIdempotencyKey(String idempotencyKey, String orderId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true; // 没提供幂等键就不去重
        }
        return idempotencyIndex.putIfAbsent(idempotencyKey, orderId) == null;
    }

    public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        String orderId = idempotencyIndex.get(idempotencyKey);
        return orderId == null ? Optional.empty() : findByOrderId(orderId);
    }

    public void save(Order order) {
        orders.put(order.getOrderId(), order);
    }

    public Optional<Order> findByOrderId(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    /**
     * 创建失败时回滚幂等键占位，否则一次失败会把这个键永久占死。
     * 注意：只在「本线程确实抢到了这个键」时调用，见 OrderService 的 try/catch。
     */
    public void releaseIdempotencyKey(String idempotencyKey, String orderId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        idempotencyIndex.remove(idempotencyKey, orderId);
    }

    /** 供测试统计订单总数 */
    public int size() {
        return orders.size();
    }
}
