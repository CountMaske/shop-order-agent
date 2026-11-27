shop-order-agent
> 电商商城订单Agent

摘要 : 用户跟Agent説帮我下单*** 商品、我下单的*** 商品订单支付了没、我不要这个订单了 取消这个单子吧、我取消的那单有没有重复扣我钱了
    在説帮我下单*** 商品前, 插入2步 : 跟Agent説 搜索商品、计算总价

### 运行方式
1. 
    仓库根目录下, 打开docker desktop
```cmd
docker compose up -d
```

2. 
    打开IDEA
backend\src\main\java\com\countmaske\orderagent\BackendApplication.java
运行main方法




### 系统设计
##### 设计思路
Agent Runtime
Step one 接收用户输入
Step two 判断是直接回复，还是调用工具
Step three 调用工具
Step four 根据工具结果判断是继续loop，还是返回结果给用户

* Step two + Step three + Step four
1. 搜索商品/查库存 search
2. 算总价 calculator
3. 下单 create_order
4. 查支付状态 query_order
5. 取消订单 + 恢复库存 cancel_order

agent runtime : 上述只讲了Loop大致步骤、工具相关
架构设计题4 : （重试）  帮我下单*** 商品  可能出现重复订单    +    我不要这个订单了 取消这个单子吧  可能出现重复恢复库存
架构设计题5 :  (数据一致性) 我下单的*** 商品订单支付了没    +     我取消的那单有没有重复扣我钱了

##### 工具相关
> 实现工具注册机制（每个工具包含名称、描述、参数 Schema），LLM 基于 Schema 自主决策调用。需实现 LLM 输出的解析逻辑，提取思考过程、工具调用或最终答案。

* 下单 create_order
    在下单时, 会返回给用户去确认是否下单


### memory的召回时机与放置方式



