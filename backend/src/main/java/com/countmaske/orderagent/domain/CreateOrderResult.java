package com.countmaske.orderagent.domain;

import java.util.List;

/**
 * create_order 工具的返回结果。
 *
 * needConfirm=true  : 闸门未开，只返回商品与总价，等待用户确认（无任何副作用）
 * needConfirm=false : 订单已真正创建/命中幂等，返回订单快照
 */
public class CreateOrderResult {

    private final boolean needConfirm;
    private final String orderId;
    private final String status;
    private final double total;
    private final List<OrderItem> items;
    private final String message;

    private CreateOrderResult(boolean needConfirm, String orderId, String status,
                              double total, List<OrderItem> items, String message) {
        this.needConfirm = needConfirm;
        this.orderId = orderId;
        this.status = status;
        this.total = total;
        this.items = items;
        this.message = message;
    }

    public static CreateOrderResult needConfirm(double total, List<OrderItem> items) {
        return new CreateOrderResult(true, null, null, total, items,
            "订单预览：请确认后再次调用 create_order 并设置 confirm=true 完成下单");
    }

    public static CreateOrderResult created(Order order) {
        return new CreateOrderResult(false, order.getOrderId(),
            order.getStatus().name(), order.getTotalAmount(), order.getItems(), "下单成功");
    }

    public static CreateOrderResult idempotentHit(Order existing) {
        return new CreateOrderResult(false, existing.getOrderId(),
            existing.getStatus().name(), existing.getTotalAmount(), existing.getItems(),
            "幂等命中：同一幂等键已创建过订单，未重复扣减库存");
    }

    public boolean isNeedConfirm() {
        return needConfirm;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getStatus() {
        return status;
    }

    public double getTotal() {
        return total;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public String getMessage() {
        return message;
    }
}
