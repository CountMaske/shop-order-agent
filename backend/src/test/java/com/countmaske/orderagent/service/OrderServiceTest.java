package com.countmaske.orderagent.service;

import com.countmaske.orderagent.domain.CreateOrderResult;
import com.countmaske.orderagent.domain.Order;
import com.countmaske.orderagent.repository.InventoryRepository;
import com.countmaske.orderagent.repository.OrderRepository;
import com.countmaske.orderagent.repository.ProductCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订单链路 4 个核心场景（Q7：跑通即可，不追求完整用例体系）。
 * 纯领域层测试，不启动 Spring、不调用 LLM。
 */
class OrderServiceTest {

    private OrderRepository orderRepository;
    private InventoryRepository inventoryRepository;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderRepository = new OrderRepository();
        inventoryRepository = new InventoryRepository();
        orderService = new OrderService(orderRepository, inventoryRepository, new ProductCatalog());
    }

    private OrderService.ItemRequest item(String id, int qty) {
        return new OrderService.ItemRequest(id, qty);
    }

    /** confirm=true 创建一笔订单，返回订单号 */
    private String create(String idempotencyKey, int qty) {
        CreateOrderResult result = orderService.createOrder(
            "u1", List.of(item("1", qty)), true, idempotencyKey);
        return result.getOrderId();
    }

    @Test
    @DisplayName("场景1 重复下单：同一幂等键两次只创建一笔订单，库存只扣一次")
    void duplicateOrderIsIdempotent() {
        String a = create("KEY-1", 2);
        String b = create("KEY-1", 2);

        assertEquals(a, b, "同一幂等键必须返回同一订单号");
        assertEquals(1, orderRepository.size(), "只应存在一笔订单");
        assertEquals(98, inventoryRepository.getStock("1"), "库存 100 只扣 2，不能扣两次变 96");
    }

    @Test
    @DisplayName("场景2 重复取消：取消两次库存只恢复一次")
    void duplicateCancelRestoresStockOnce() {
        String orderId = create("KEY-2", 3);
        assertEquals(97, inventoryRepository.getStock("1"));

        orderService.cancelOrder(orderId);
        orderService.cancelOrder(orderId);

        assertEquals(100, inventoryRepository.getStock("1"), "取消两次库存只能回到 100，不能变 103");
        assertEquals("CANCELLED", orderService.queryOrder(orderId).getStatus().name());
    }

    @Test
    @DisplayName("场景3 支付后拒绝取消：PAID 订单取消抛异常，库存不变")
    void paidOrderCannotBeCancelled() {
        String orderId = create("KEY-3", 4);
        orderService.markPaid(orderId);

        assertThrows(IllegalStateException.class, () -> orderService.cancelOrder(orderId));
        assertEquals(96, inventoryRepository.getStock("1"), "支付后拒绝取消，库存不应被恢复");
        assertEquals("PAID", orderService.queryOrder(orderId).getStatus().name());
    }

    @Test
    @DisplayName("场景4 库存不足回滚：多商品中有库存不足，已扣商品全部回滚")
    void insufficientStockRollsBack() {
        // 商品1 库存 100，商品3 库存 100；第 2 项要 200 触发扣减失败
        CreateOrderResult preview = orderService.createOrder(
            "u1",
            List.of(item("1", 5), item("3", 200)),
            false,
            "KEY-4");
        assertTrue(preview.isNeedConfirm(), "confirm=false 只返回预览");
        assertEquals(100, inventoryRepository.getStock("1"), "预览阶段不扣库存");

        assertThrows(IllegalStateException.class, () -> orderService.createOrder(
            "u1", List.of(item("1", 5), item("3", 200)), true, "KEY-4"));

        assertEquals(100, inventoryRepository.getStock("1"), "商品1 已扣的 5 件必须回滚");
        assertEquals(100, inventoryRepository.getStock("3"));
        assertEquals(0, orderRepository.size(), "失败不应落库");
    }

    @Test
    @DisplayName("闸门：confirm=false 返回总价预览，不建单不扣库存")
    void confirmGateReturnsPreviewOnly() {
        CreateOrderResult preview = orderService.createOrder(
            "u1", List.of(item("2", 2), item("3", 1)), false, "KEY-5");

        assertTrue(preview.isNeedConfirm());
        assertEquals(11.0, preview.getTotal(), "2*3 + 1*5 = 11");
        assertEquals(0, orderRepository.size());
        assertEquals(100, inventoryRepository.getStock("2"));
    }
}
