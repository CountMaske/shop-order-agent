package com.countmaske.orderagent.tool;

import com.countmaske.orderagent.domain.Product;
import com.countmaske.orderagent.repository.ProductCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 工具 2：calculator —— 计算购买若干商品的总价。
 *
 * 价格以商品目录为准（和 create_order 同源），不信任 LLM 传入的价格。
 */
@Component
public class CalculatorTool implements Tool {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProductCatalog catalog;

    public CalculatorTool(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "计算购买若干商品的总价。传入 items 数组，每项含 productId 和 quantity，返回总价（CNY）";
    }

    @Override
    public JsonNode schema() {
        ObjectNode itemProps = mapper.createObjectNode();
        itemProps.set("productId", mapper.createObjectNode()
            .put("type", "string").put("description", "商品 ID，如 1/2/3"));
        itemProps.set("quantity", mapper.createObjectNode()
            .put("type", "integer").put("description", "购买数量"));

        ObjectNode itemSchema = mapper.createObjectNode();
        itemSchema.put("type", "object");
        itemSchema.set("properties", itemProps);

        ObjectNode itemsSchema = mapper.createObjectNode();
        itemsSchema.put("type", "array");
        itemsSchema.put("description", "商品列表，每项含 productId 和 quantity");
        itemsSchema.set("items", itemSchema);

        ObjectNode props = mapper.createObjectNode();
        props.set("items", itemsSchema);

        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        root.set("properties", props);
        return root;
    }

    @Override
    public String execute(JsonNode arguments) {
        JsonNode items = arguments.path("items");
        double total = 0.0;
        for (JsonNode item : items) {
            String productId = item.path("productId").asText();
            int quantity = item.path("quantity").asInt(1);
            Product product = catalog.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在: " + productId));
            total += product.price() * quantity;
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("total", Math.round(total * 100.0) / 100.0);
        result.put("currency", "CNY");
        return result.toString();
    }
}
