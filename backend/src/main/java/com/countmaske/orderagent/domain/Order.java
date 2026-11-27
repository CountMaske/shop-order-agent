package com.countmaske.orderagent.domain;

import java.util.List;

/**
 * 订单聚合根。
 *
 * 设计要点：
 * - idempotencyKey：幂等键，同一个 key 只能创建一笔订单（防止重试重复下单）
 * - version：乐观锁版本号，状态流转用 CAS，防止并发下重复恢复库存
 * - items：下单瞬间的商品快照（价格锁死），避免事后改价影响历史订单
 */
public class Order {

    private final String orderId;
    private final String userId;
    private final List<OrderItem> items;
    private final double totalAmount;
    private final String idempotencyKey;
    private final long createdAt;

    private OrderStatus status;
    private long version;

    public Order(String orderId, String userId, List<OrderItem> items, double totalAmount,
                 String idempotencyKey) {
        this.orderId = orderId;
        this.userId = userId;
        this.items = List.copyOf(items);
        this.totalAmount = totalAmount;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = System.currentTimeMillis();
        this.status = OrderStatus.CREATED;
        this.version = 0L;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getUserId() {
        return userId;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public double getTotalAmount() {
        return totalAmount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
