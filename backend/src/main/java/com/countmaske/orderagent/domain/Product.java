package com.countmaske.orderagent.domain;

/**
 * 商品定义（不可变）
 * 库存数量单独放在 InventoryRepository 里，用版本号做 CAS，见 Stock。
 */
public record Product(String id, String name, double price) {
}
