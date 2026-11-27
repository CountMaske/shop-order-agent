package com.countmaske.orderagent.repository;

import com.countmaske.orderagent.domain.Product;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存商品目录（mock 数据）。
 *
 * 关键点：下单时商品价格以目录为准做「快照」，不信任 LLM 在 arguments 里传的价格。
 * 否则模型幻觉出一个 1 分钱的价格就能把订单算错——这是工具设计的安全边界。
 */
@Repository
public class ProductCatalog {

    private final Map<String, Product> products = new ConcurrentHashMap<>();

    public ProductCatalog() {
        products.put("1", new Product("1", "农夫山泉 550ml", 2.0));
        products.put("2", new Product("2", "可口可乐 330ml", 3.0));
        products.put("3", new Product("3", "元气森林气泡水 480ml", 5.0));
    }

    public Optional<Product> findById(String id) {
        return Optional.ofNullable(products.get(id));
    }

    public List<Product> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.copyOf(products.values());
        }
        return products.values().stream()
            .filter(p -> p.name().contains(keyword))
            .toList();
    }
}
