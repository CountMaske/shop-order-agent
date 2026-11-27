package com.countmaske.orderagent.tool;

import com.countmaske.orderagent.domain.Product;
import com.countmaske.orderagent.repository.InventoryRepository;
import com.countmaske.orderagent.repository.ProductCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具 1：search —— 搜索商品 / 查库存。
 */
@Component
public class SearchTool implements Tool {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProductCatalog catalog;
    private final InventoryRepository inventory;

    public SearchTool(ProductCatalog catalog, InventoryRepository inventory) {
        this.catalog = catalog;
        this.inventory = inventory;
    }

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String description() {
        return "搜索商品信息，返回匹配商品的名称、单价和实时库存；下单前用它确认商品是否存在及库存是否充足";
    }

    @Override
    public JsonNode schema() {
        return mapper.createObjectNode()
            .put("type", "object")
            .set("properties", mapper.createObjectNode()
                .set("keyword", mapper.createObjectNode()
                    .put("type", "string")
                    .put("description", "搜索关键词，商品名称包含该词即命中；留空返回全部商品")));
    }

    @Override
    public String execute(JsonNode arguments) {
        String keyword = arguments.path("keyword").asText("");
        List<Product> products = catalog.search(keyword);

        ArrayNode result = mapper.createArrayNode();
        for (Product product : products) {
            ObjectNode item = result.addObject();
            item.put("id", product.id());
            item.put("name", product.name());
            item.put("price", product.price());
            item.put("stock", inventory.getStock(product.id()));
        }
        return result.toString();
    }
}
