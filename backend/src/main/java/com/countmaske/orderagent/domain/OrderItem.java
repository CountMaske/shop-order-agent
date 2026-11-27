package com.countmaske.orderagent.domain;

/**
 * 订单项：下单那一刻锁定的商品快照（价格与名称快照，不随商品表后续变化）
 */
public record OrderItem(String productId, String name, double unitPrice, int quantity) {
}
