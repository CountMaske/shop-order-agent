package com.countmaske.orderagent.service;

import com.countmaske.orderagent.domain.CreateOrderResult;
import com.countmaske.orderagent.domain.Order;
import com.countmaske.orderagent.domain.OrderItem;
import com.countmaske.orderagent.domain.OrderStatus;
import com.countmaske.orderagent.domain.Product;
import com.countmaske.orderagent.repository.InventoryRepository;
import com.countmaske.orderagent.repository.OrderRepository;
import com.countmaske.orderagent.repository.ProductCatalog;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 订单领域服务：下单 / 查支付状态 / 取消并恢复库存。
 *
 * 这一层是「写—读—写」三个订单工具的业务内核，集中处理四个一致性问题：
 * 1. 重试重复下单  -> 幂等键 claim + 命中直接返回旧单
 * 2. 重复取消      -> 状态机幂等，CANCELLED 幂等返回，不二次恢复库存
 * 3. 已支付取消    -> 状态校验拒绝
 * 4. 库存不足      -> 预检 + 扣减失败整体回滚，不留部分扣减
 *
 * 本轮纯内存实现，不引入 Redis/MySQL；并发安全靠 ConcurrentHashMap + AtomicInteger CAS。
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final ProductCatalog productCatalog;

    public OrderService(OrderRepository orderRepository, InventoryRepository inventoryRepository,
                        ProductCatalog productCatalog) {
        this.orderRepository = orderRepository;
        this.inventoryRepository = inventoryRepository;
        this.productCatalog = productCatalog;
    }

    /**
     * 下单（两阶段 confirm 闸门）。
     *
     * confirm=false：只做参数校验与价格快照，返回总价与商品，不建单、不扣库存（无副作用）。
     * confirm=true ：走幂等校验 + 库存预检 + 扣减 + 建单。
     *
     * @param items         商品明细，每项含 productId 与 quantity
     * @param confirm       用户是否已确认
     * @param idempotencyKey 幂等键；相同键重复请求只创建一笔订单
     */
    public CreateOrderResult createOrder(String userId, List<ItemRequest> items,
                                         boolean confirm, String idempotencyKey) {
        // 1. 先做价格快照：以商品目录为准，忽略 LLM 传入的价格，防幻觉篡改
        List<OrderItem> snapshot = new ArrayList<>();
        double total = 0.0;
        for (ItemRequest request : items) {
            Product product = productCatalog.findById(request.productId())
                .orElseThrow(() -> new IllegalArgumentException("商品不存在: " + request.productId()));
            int quantity = request.quantity();
            if (quantity <= 0) {
                throw new IllegalArgumentException("商品数量必须为正数: " + request.productId());
            }
            OrderItem line = new OrderItem(product.id(), product.name(), product.price(), quantity);
            snapshot.add(line);
            total += product.price() * quantity;
        }

        // 2. confirm 闸门：未确认时只返回预览，绝不产生副作用
        if (!confirm) {
            return CreateOrderResult.needConfirm(total, snapshot);
        }

        // 3. 幂等命中：同一幂等键已建过单，直接返回旧单，不重复扣库存
        Optional<Order> existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return CreateOrderResult.idempotentHit(existing.get());
        }

        // 4. 库存预检：所有商品库存都足够才动手，避免部分扣减
        for (OrderItem line : snapshot) {
            if (inventoryRepository.getStock(line.productId()) < line.quantity()) {
                throw new IllegalStateException("库存不足: " + line.name()
                    + " 需要 " + line.quantity() + "，剩余 " + inventoryRepository.getStock(line.productId()));
            }
        }

        String orderId = "ORD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // 5. 抢占幂等键：putIfAbsent 原子，抢占失败说明并发下别人已建单
        if (!orderRepository.claimIdempotencyKey(idempotencyKey, orderId)) {
            Optional<Order> winner = orderRepository.findByIdempotencyKey(idempotencyKey);
            if (winner.isPresent()) {
                return CreateOrderResult.idempotentHit(winner.get());
            }
            throw new IllegalStateException("幂等键抢占冲突，请重试");
        }

        // 6. 扣减库存：任一商品扣减失败，整体回滚已扣部分 + 释放幂等键占位
        List<OrderItem> deducted = new ArrayList<>();
        try {
            for (OrderItem line : snapshot) {
                if (!inventoryRepository.deduct(line.productId(), line.quantity())) {
                    throw new IllegalStateException("库存不足（并发扣减）: " + line.name());
                }
                deducted.add(line);
            }
        } catch (RuntimeException e) {
            for (OrderItem line : deducted) {
                inventoryRepository.restore(line.productId(), line.quantity());
            }
            orderRepository.releaseIdempotencyKey(idempotencyKey, orderId);
            throw e;
        }

        // 7. 建单落库
        Order order = new Order(orderId, userId, snapshot, total, idempotencyKey);
        orderRepository.save(order);
        return CreateOrderResult.created(order);
    }

    /**
     * 查支付状态（只读）。
     */
    public Order queryOrder(String orderId) {
        return orderRepository.findByOrderId(orderId)
            .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderId));
    }

    /**
     * 取消订单 + 恢复库存。
     *
     * 幂等与状态校验：
     * - CANCELLED -> 幂等成功，直接返回，不二次恢复库存（防重复取消刷库存）
     * - PAID      -> 拒绝取消（已支付要走退款流程，不能只恢复库存）
     * - CREATED   -> CAS 抢状态，成功者才恢复库存
     *
     * 用 synchronized(order) + version 校验模拟乐观锁：
     * 抢状态与恢复库存必须在同一临界区内，否则并发取消会重复恢复库存。
     */
    public Order cancelOrder(String orderId) {
        Order order = orderRepository.findByOrderId(orderId)
            .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderId));

        synchronized (order) {
            if (order.getStatus() == OrderStatus.CANCELLED) {
                return order; // 幂等命中，库存已在首次取消时恢复
            }
            if (order.getStatus() == OrderStatus.PAID) {
                throw new IllegalStateException("订单已支付，不能直接取消，请走退款流程: " + orderId);
            }
            if (order.getStatus() != OrderStatus.CREATED) {
                throw new IllegalStateException("订单状态不允许取消: " + order.getStatus());
            }

            long expectedVersion = order.getVersion();
            // CAS 语义：只有版本号仍等于期望值，才推进状态并恢复库存
            if (order.getVersion() != expectedVersion) {
                throw new IllegalStateException("订单已被并发修改，请重试: " + orderId);
            }
            for (OrderItem line : order.getItems()) {
                inventoryRepository.restore(line.productId(), line.quantity());
            }
            order.setStatus(OrderStatus.CANCELLED);
            order.setVersion(expectedVersion + 1);
            return order;
        }
    }

    /**
     * 支付状态推进钩子（供测试/定时钩子使用，本轮不接真实支付回调）。
     * CREATED -> PAID；其他状态忽略。
     */
    public void markPaid(String orderId) {
        Order order = orderRepository.findByOrderId(orderId)
            .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderId));
        synchronized (order) {
            if (order.getStatus() == OrderStatus.CREATED) {
                order.setStatus(OrderStatus.PAID);
                order.setVersion(order.getVersion() + 1);
            }
        }
    }

    /** 下单请求项：只要 productId 和 quantity，价格由目录决定 */
    public record ItemRequest(String productId, int quantity) {
    }
}
