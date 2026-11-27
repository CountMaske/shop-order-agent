package com.countmaske.orderagent.domain;

/**
 * 订单状态机
 *
 * CREATED --取消--> CANCELLED（恢复库存）
 * CREATED --支付-->  PAID
 * PAID    --取消-->  拒绝（业务错误）
 * CANCELLED --重复取消--> 幂等返回，不重复恢复库存
 */
public enum OrderStatus {
    /** 已创建、待支付 */
    CREATED,
    /** 已支付 */
    PAID,
    /** 已取消（库存已恢复） */
    CANCELLED,
    /** 已过期 */
    EXPIRED
}
