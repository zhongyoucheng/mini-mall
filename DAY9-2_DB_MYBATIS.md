---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '3a4250aa-96ea-4ef5-b630-dbe7799e42f1'
  PropagateID: '3a4250aa-96ea-4ef5-b630-dbe7799e42f1'
  ReservedCode1: 'c32123a6-207f-4f26-8aed-64d42057202b'
  ReservedCode2: 'c32123a6-207f-4f26-8aed-64d42057202b'
---

# 阶段九 Day 2：数据库设计 + 实体类 + MyBatis 整合

> mini-mall 电商订单系统 | 教学文档 v1.0 | 2026-09-15

---

## 一、今日目标

设计 3 个数据库 8 张表，创建对应的实体类、Mapper 接口和 XML 映射文件，让 3 个业务服务（user/product/order）都能完成基础 CRUD。

## 二、数据库设计

### 2.1 表结构总览

| 数据库 | 表 | 用途 |
|--------|-----|------|
| mini_mall_user | t_user | 用户表（注册/登录） |
| mini_mall_product | t_product | 商品表 |
| mini_mall_product | t_stock | 库存表 |
| mini_mall_order | t_order | 订单主表 |
| mini_mall_order | t_order_item | 订单明细表 |
| mini_mall_order | t_order_log | 订单状态变更日志 |

> 说明：按微服务"数据私有"原则，3 个服务各用独立数据库。每库只放本服务的表。

### 2.2 建表 SQL

#### user-service：mini_mall_user 库

```sql
CREATE DATABASE IF NOT EXISTS mini_mall_user DEFAULT CHARACTER SET utf8mb4;

USE mini_mall_user;

CREATE TABLE IF NOT EXISTS t_user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    username    VARCHAR(50)  NOT NULL UNIQUE COMMENT '用户名',
    password    VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 加密）',
    nickname    VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    email       VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    phone       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    role        VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色：USER/ADMIN',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB COMMENT='用户表';
```

#### product-service：mini_mall_product 库

```sql
CREATE DATABASE IF NOT EXISTS mini_mall_product DEFAULT CHARACTER SET utf8mb4;

USE mini_mall_product;

CREATE TABLE IF NOT EXISTS t_product (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    name        VARCHAR(100) NOT NULL COMMENT '商品名称',
    description VARCHAR(500) DEFAULT NULL COMMENT '商品描述',
    price       DECIMAL(10,2) NOT NULL COMMENT '单价',
    category    VARCHAR(50)  DEFAULT NULL COMMENT '分类',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1上架 0下架',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB COMMENT='商品表';

CREATE TABLE IF NOT EXISTS t_stock (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    product_id  BIGINT   NOT NULL COMMENT '商品ID',
    quantity    INT      NOT NULL DEFAULT 0 COMMENT '库存数量',
    locked      INT      NOT NULL DEFAULT 0 COMMENT '锁定数量（下单占用）',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_product_id (product_id)
) ENGINE=InnoDB COMMENT='库存表';
```

#### order-service：mini_mall_order 库

```sql
CREATE DATABASE IF NOT EXISTS mini_mall_order DEFAULT CHARACTER SET utf8mb4;

USE mini_mall_order;

CREATE TABLE IF NOT EXISTS t_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    order_no      VARCHAR(32)  NOT NULL UNIQUE COMMENT '订单号',
    user_id       BIGINT       NOT NULL COMMENT '下单用户ID',
    total_amount  DECIMAL(10,2) NOT NULL COMMENT '订单总金额',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0待支付 1已支付 2已取消 3超时取消',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB COMMENT='订单主表';

CREATE TABLE IF NOT EXISTS t_order_item (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    order_id    BIGINT       NOT NULL COMMENT '订单ID',
    product_id  BIGINT       NOT NULL COMMENT '商品ID',
    product_name VARCHAR(100) NOT NULL COMMENT '商品名称快照',
    price       DECIMAL(10,2) NOT NULL COMMENT '下单时单价',
    quantity    INT          NOT NULL COMMENT '购买数量',
    sub_total   DECIMAL(10,2) NOT NULL COMMENT '小计金额'
) ENGINE=InnoDB COMMENT='订单明细表';

CREATE TABLE IF NOT EXISTS t_order_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    order_id    BIGINT      NOT NULL COMMENT '订单ID',
    action      VARCHAR(50) NOT NULL COMMENT '操作：CREATE/PAY/CANCEL/TIMEOUT',
    from_status TINYINT     DEFAULT NULL COMMENT '原状态',
    to_status   TINYINT     DEFAULT NULL COMMENT '新状态',
    remark      VARCHAR(200) DEFAULT NULL COMMENT '备注',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间'
) ENGINE=InnoDB COMMENT='订单状态日志表';
```

### 2.3 建表脚本执行

```bash
# 在 MySQL 中执行建表脚本（把上面的 SQL 保存为 /tmp/mini_mall_ddl.sql）
/usr/local/mysql/bin/mysql -u root -p'zyC191380!!' < /tmp/mini_mall_ddl.sql

# 验证
/usr/local/mysql/bin/mysql -u root -p'zyC191380!!' -e "SHOW TABLES FROM mini_mall_user; SHOW TABLES FROM mini_mall_product; SHOW TABLES FROM mini_mall_order;"
```

---

## 三、实体类

### 3.1 user-service 实体

**文件：`user-service/src/main/java/com/minimall/user/model/User.java`**

```java
package com.minimall.user.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class User {
    private Long id;
    private String username;
    private String password;
    private String nickname;
    private String email;
    private String phone;
    private String role;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

### 3.2 product-service 实体

**文件：`product-service/src/main/java/com/minimall/product/model/Product.java`**

```java
package com.minimall.product.model;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class Product {
    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private String category;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

**文件：`product-service/src/main/java/com/minimall/product/model/Stock.java`**

```java
package com.minimall.product.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Stock {
    private Long id;
    private Long productId;
    private Integer quantity;
    private Integer locked;
    private LocalDateTime updateTime;
}
```

### 3.3 order-service 实体

**文件：`order-service/src/main/java/com/minimall/order/model/Order.java`**

```java
package com.minimall.order.model;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class Order {
    private Long id;
    private String orderNo;
    private Long userId;
    private BigDecimal totalAmount;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

**文件：`order-service/src/main/java/com/minimall/order/model/OrderItem.java`**

```java
package com.minimall.order.model;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class OrderItem {
    private Long id;
    private Long orderId;
    private Long productId;
    private String productName;
    private BigDecimal price;
    private Integer quantity;
    private BigDecimal subTotal;
}
```

**文件：`order-service/src/main/java/com/minimall/order/model/OrderLog.java`**

```java
package com.minimall.order.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class OrderLog {
    private Long id;
    private Long orderId;
    private String action;
    private Integer fromStatus;
    private Integer toStatus;
    private String remark;
    private LocalDateTime createTime;
}
```

---

## 四、Mapper 接口 + XML

### 4.1 user-service：UserMapper

**接口：`user-service/src/main/java/com/minimall/user/mapper/UserMapper.java`**

```java
package com.minimall.user.mapper;

import com.minimall.user.model.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

    int insert(User user);

    User findById(@Param("id") Long id);

    User findByUsername(@Param("username") String username);

    int update(User user);

    int deleteById(@Param("id") Long id);
}
```

**XML：`user-service/src/main/resources/mapper/UserMapper.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.minimall.user.mapper.UserMapper">

    <resultMap id="UserMap" type="com.minimall.user.model.User">
        <id property="id" column="id"/>
        <result property="username" column="username"/>
        <result property="password" column="password"/>
        <result property="nickname" column="nickname"/>
        <result property="email" column="email"/>
        <result property="phone" column="phone"/>
        <result property="role" column="role"/>
        <result property="createTime" column="create_time"/>
        <result property="updateTime" column="update_time"/>
    </resultMap>

    <insert id="insert" parameterType="com.minimall.user.model.User" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO t_user (username, password, nickname, email, phone, role)
        VALUES (#{username}, #{password}, #{nickname}, #{email}, #{phone}, #{role})
    </insert>

    <select id="findById" resultMap="UserMap">
        SELECT * FROM t_user WHERE id = #{id}
    </select>

    <select id="findByUsername" resultMap="UserMap">
        SELECT * FROM t_user WHERE username = #{username}
    </select>

    <update id="update" parameterType="com.minimall.user.model.User">
        UPDATE t_user
        SET nickname = #{nickname}, email = #{email}, phone = #{phone}, role = #{role}
        WHERE id = #{id}
    </update>

    <delete id="deleteById">
        DELETE FROM t_user WHERE id = #{id}
    </delete>
</mapper>
```

### 4.2 product-service：ProductMapper + StockMapper

**接口：`product-service/src/main/java/com/minimall/product/mapper/ProductMapper.java`**

```java
package com.minimall.product.mapper;

import com.minimall.product.model.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ProductMapper {

    int insert(Product product);

    Product findById(@Param("id") Long id);

    List<Product> findAll();

    List<Product> findByCategory(@Param("category") String category);

    int update(Product product);

    int deleteById(@Param("id") Long id);
}
```

**XML：`product-service/src/main/resources/mapper/ProductMapper.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.minimall.product.mapper.ProductMapper">

    <resultMap id="ProductMap" type="com.minimall.product.model.Product">
        <id property="id" column="id"/>
        <result property="name" column="name"/>
        <result property="description" column="description"/>
        <result property="price" column="price"/>
        <result property="category" column="category"/>
        <result property="status" column="status"/>
        <result property="createTime" column="create_time"/>
        <result property="updateTime" column="update_time"/>
    </resultMap>

    <insert id="insert" parameterType="com.minimall.product.model.Product" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO t_product (name, description, price, category, status)
        VALUES (#{name}, #{description}, #{price}, #{category}, #{status})
    </insert>

    <select id="findById" resultMap="ProductMap">
        SELECT * FROM t_product WHERE id = #{id}
    </select>

    <select id="findAll" resultMap="ProductMap">
        SELECT * FROM t_product ORDER BY id DESC
    </select>

    <select id="findByCategory" resultMap="ProductMap">
        SELECT * FROM t_product WHERE category = #{category}
    </select>

    <update id="update" parameterType="com.minimall.product.model.Product">
        UPDATE t_product
        SET name = #{name}, description = #{description}, price = #{price},
            category = #{category}, status = #{status}
        WHERE id = #{id}
    </update>

    <delete id="deleteById">
        DELETE FROM t_product WHERE id = #{id}
    </delete>
</mapper>
```

**接口：`product-service/src/main/java/com/minimall/product/mapper/StockMapper.java`**

```java
package com.minimall.product.mapper;

import com.minimall.product.model.Stock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockMapper {

    int insert(Stock stock);

    Stock findByProductId(@Param("productId") Long productId);

    int updateQuantity(@Param("productId") Long productId, @Param("quantity") Integer quantity);
}
```

**XML：`product-service/src/main/resources/mapper/StockMapper.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.minimall.product.mapper.StockMapper">

    <resultMap id="StockMap" type="com.minimall.product.model.Stock">
        <id property="id" column="id"/>
        <result property="productId" column="product_id"/>
        <result property="quantity" column="quantity"/>
        <result property="locked" column="locked"/>
        <result property="updateTime" column="update_time"/>
    </resultMap>

    <insert id="insert" parameterType="com.minimall.product.model.Stock" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO t_stock (product_id, quantity, locked)
        VALUES (#{productId}, #{quantity}, #{locked})
    </insert>

    <select id="findByProductId" resultMap="StockMap">
        SELECT * FROM t_stock WHERE product_id = #{productId}
    </select>

    <update id="updateQuantity">
        UPDATE t_stock SET quantity = #{quantity} WHERE product_id = #{productId}
    </update>
</mapper>
```

### 4.3 order-service：OrderMapper + OrderItemMapper + OrderLogMapper

**接口：`order-service/src/main/java/com/minimall/order/mapper/OrderMapper.java`**

```java
package com.minimall.order.mapper;

import com.minimall.order.model.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface OrderMapper {

    int insert(Order order);

    Order findById(@Param("id") Long id);

    Order findByOrderNo(@Param("orderNo") String orderNo);

    List<Order> findByUserId(@Param("userId") Long userId);

    List<Order> findAll();

    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    int updateStatusByOrderNo(@Param("orderNo") String orderNo, @Param("status") Integer status);
}
```

**XML：`order-service/src/main/resources/mapper/OrderMapper.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.minimall.order.mapper.OrderMapper">

    <resultMap id="OrderMap" type="com.minimall.order.model.Order">
        <id property="id" column="id"/>
        <result property="orderNo" column="order_no"/>
        <result property="userId" column="user_id"/>
        <result property="totalAmount" column="total_amount"/>
        <result property="status" column="status"/>
        <result property="createTime" column="create_time"/>
        <result property="updateTime" column="update_time"/>
    </resultMap>

    <insert id="insert" parameterType="com.minimall.order.model.Order" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO t_order (order_no, user_id, total_amount, status)
        VALUES (#{orderNo}, #{userId}, #{totalAmount}, #{status})
    </insert>

    <select id="findById" resultMap="OrderMap">
        SELECT * FROM t_order WHERE id = #{id}
    </select>

    <select id="findByOrderNo" resultMap="OrderMap">
        SELECT * FROM t_order WHERE order_no = #{orderNo}
    </select>

    <select id="findByUserId" resultMap="OrderMap">
        SELECT * FROM t_order WHERE user_id = #{userId} ORDER BY id DESC
    </select>

    <select id="findAll" resultMap="OrderMap">
        SELECT * FROM t_order ORDER BY id DESC
    </select>

    <update id="updateStatus">
        UPDATE t_order SET status = #{status} WHERE id = #{id}
    </update>

    <update id="updateStatusByOrderNo">
        UPDATE t_order SET status = #{status} WHERE order_no = #{orderNo}
    </update>
</mapper>
```

**接口：`order-service/src/main/java/com/minimall/order/mapper/OrderItemMapper.java`**

```java
package com.minimall.order.mapper;

import com.minimall.order.model.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface OrderItemMapper {

    int insert(OrderItem item);

    int batchInsert(@Param("list") List<OrderItem> items);

    List<OrderItem> findByOrderId(@Param("orderId") Long orderId);
}
```

**XML：`order-service/src/main/resources/mapper/OrderItemMapper.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.minimall.order.mapper.OrderItemMapper">

    <resultMap id="OrderItemMap" type="com.minimall.order.model.OrderItem">
        <id property="id" column="id"/>
        <result property="orderId" column="order_id"/>
        <result property="productId" column="product_id"/>
        <result property="productName" column="product_name"/>
        <result property="price" column="price"/>
        <result property="quantity" column="quantity"/>
        <result property="subTotal" column="sub_total"/>
    </resultMap>

    <insert id="insert" parameterType="com.minimall.order.model.OrderItem" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO t_order_item (order_id, product_id, product_name, price, quantity, sub_total)
        VALUES (#{orderId}, #{productId}, #{productName}, #{price}, #{quantity}, #{subTotal})
    </insert>

    <insert id="batchInsert">
        INSERT INTO t_order_item (order_id, product_id, product_name, price, quantity, sub_total)
        VALUES
        <foreach collection="list" item="item" separator=",">
            (#{item.orderId}, #{item.productId}, #{item.productName}, #{item.price}, #{item.quantity}, #{item.subTotal})
        </foreach>
    </insert>

    <select id="findByOrderId" resultMap="OrderItemMap">
        SELECT * FROM t_order_item WHERE order_id = #{orderId}
    </select>
</mapper>
```

**接口：`order-service/src/main/java/com/minimall/order/mapper/OrderLogMapper.java`**

```java
package com.minimall.order.mapper;

import com.minimall.order.model.OrderLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface OrderLogMapper {

    int insert(OrderLog log);

    List<OrderLog> findByOrderId(@Param("orderId") Long orderId);
}
```

**XML：`order-service/src/main/resources/mapper/OrderLogMapper.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.minimall.order.mapper.OrderLogMapper">

    <resultMap id="OrderLogMap" type="com.minimall.order.model.OrderLog">
        <id property="id" column="id"/>
        <result property="orderId" column="order_id"/>
        <result property="action" column="action"/>
        <result property="fromStatus" column="from_status"/>
        <result property="toStatus" column="to_status"/>
        <result property="remark" column="remark"/>
        <result property="createTime" column="create_time"/>
    </resultMap>

    <insert id="insert" parameterType="com.minimall.order.model.OrderLog" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO t_order_log (order_id, action, from_status, to_status, remark)
        VALUES (#{orderId}, #{action}, #{fromStatus}, #{toStatus}, #{remark})
    </insert>

    <select id="findByOrderId" resultMap="OrderLogMap">
        SELECT * FROM t_order_log WHERE order_id = #{orderId} ORDER BY id ASC
    </select>
</mapper>
```

---

## 五、测试 Controller（验证 CRUD）

为了让 Mapper 可被验证，每个服务写一个简单的测试 Controller。Day 3+ 会替换成正式业务接口。

### 5.1 user-service

**文件：`user-service/src/main/java/com/minimall/user/controller/UserController.java`**

```java
package com.minimall.user.controller;

import com.minimall.user.mapper.UserMapper;
import com.minimall.user.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/users")
public class UserController {

    @Autowired
    private UserMapper userMapper;

    @GetMapping("/{id}")
    public Map<String, Object> getById(@PathVariable Long id) {
        User user = userMapper.findById(id);
        if (user == null) {
            return Map.of("code", 404, "message", "用户不存在");
        }
        user.setPassword(null);  // 不返回密码
        return Map.of("code", 200, "data", user);
    }

    @GetMapping("/check/{username}")
    public Map<String, Object> checkUsername(@PathVariable String username) {
        User user = userMapper.findByUsername(username);
        return Map.of("code", 200, "exists", user != null);
    }
}
```

### 5.2 product-service

**文件：`product-service/src/main/java/com/minimall/product/controller/ProductController.java`**

```java
package com.minimall.product.controller;

import com.minimall.product.mapper.ProductMapper;
import com.minimall.product.model.Product;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/products")
public class ProductController {

    @Autowired
    private ProductMapper productMapper;

    @GetMapping("/{id}")
    public Map<String, Object> getById(@PathVariable Long id) {
        Product product = productMapper.findById(id);
        if (product == null) {
            return Map.of("code", 404, "message", "商品不存在");
        }
        return Map.of("code", 200, "data", product);
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("code", 200, "data", productMapper.findAll());
    }
}
```

### 5.3 order-service

**文件：`order-service/src/main/java/com/minimall/order/controller/OrderController.java`**

```java
package com.minimall.order.controller;

import com.minimall.order.mapper.OrderMapper;
import com.minimall.order.model.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/orders")
public class OrderController {

    @Autowired
    private OrderMapper orderMapper;

    @GetMapping("/{id}")
    public Map<String, Object> getById(@PathVariable Long id) {
        Order order = orderMapper.findById(id);
        if (order == null) {
            return Map.of("code", 404, "message", "订单不存在");
        }
        return Map.of("code", 200, "data", order);
    }

    @GetMapping("/user/{userId}")
    public Map<String, Object> listByUser(@PathVariable Long userId) {
        return Map.of("code", 200, "data", orderMapper.findByUserId(userId));
    }
}
```

---

## 六、MyBatis 配置

MyBatis 需要扫描 Mapper 接口和 XML。在 `application.yml` 中加配置（3 个业务服务都要加）：

```yaml
mybatis:
  mapper-locations: classpath:mapper/*.xml
  type-aliases-package: com.minimall.user.model   # user-service 用这个包名
  configuration:
    map-underscore-to-camel-case: true   # 下划线转驼峰，resultMap 可简化
```

> product-service 用 `com.minimall.product.model`，order-service 用 `com.minimall.order.model`。

---

## 七、练习任务清单

### 任务 1：执行建表 SQL
- 把 2.2 节的 SQL 保存为脚本并执行
- 验证 3 库 8 表创建成功

### 任务 2：创建实体类
- user-service：`User.java`
- product-service：`Product.java` + `Stock.java`
- order-service：`Order.java` + `OrderItem.java` + `OrderLog.java`

### 任务 3：创建 Mapper 接口
- user-service：`UserMapper.java`
- product-service：`ProductMapper.java` + `StockMapper.java`
- order-service：`OrderMapper.java` + `OrderItemMapper.java` + `OrderLogMapper.java`

### 任务 4：创建 Mapper XML
- 对应 6 个 XML 文件（含 resultMap + CRUD SQL）

### 任务 5：添加 MyBatis 配置
- 3 个业务服务的 application.yml 加 `mybatis.mapper-locations` 等配置

### 任务 6：创建测试 Controller
- 3 个业务服务的测试 Controller（按第五节）

### 任务 7：编译 + 重启 + 验证
```bash
cd ~/Documents/java_project/mini-mall
~/tools/apache-maven-3.9.16/bin/mvn clean compile

# 重启 user-service / product-service / order-service
```

---

## 八、验收清单

| # | 验收项 | 验证方法 | 预期结果 |
|---|--------|----------|----------|
| 1 | 数据库创建成功 | mysql 查询 | 3 库 8 表存在 |
| 2 | 编译通过 | `mvn clean compile` | BUILD SUCCESS |
| 3 | 三服务启动成功 | 控制台无报错 | Started |
| 4 | user-service 查用户 | 插入一条数据后 `curl /users/1` | 返回用户 JSON（无密码） |
| 5 | user-service 查重名 | `curl /users/check/xxx` | exists=true/false |
| 6 | product-service 查商品 | 插入数据后 `curl /products/1` | 返回商品 JSON |
| 7 | product-service 列表 | `curl /products` | 返回商品列表 |
| 8 | order-service 查订单 | 插入数据后 `curl /orders/1` | 返回订单 JSON |
| 9 | order-service 按用户查 | `curl /orders/user/1` | 返回该用户订单列表 |
| 10 | Gateway 转发仍正常 | `curl --noproxy '*' http://[::1]:8080/users/1` | 经网关也能查询 |

---

## 九、踩坑预判

| # | 陷阱 | 症状 | 解决方案 |
|---|------|------|----------|
| 1 | **mapper XML 路径不对** | 启动报 `Invalid bound statement (not found)` | 确认 `mapper-locations: classpath:mapper/*.xml` 且 XML 的 namespace 与接口全限定名一致 |
| 2 | **resultMap 列名不对** | 查询返回字段全 null | resultMap 中 `column` 必须与数据库列名一致（`create_time` 不是 `createTime`） |
| 3 | **忘了 `useGeneratedKeys`** | 插入后 `getId()` 为 null | insert 标签加 `useGeneratedKeys="true" keyProperty="id"` |
| 4 | **@Mapper 注解缺失** | 启动报 `No qualifying bean of type 'UserMapper'` | 接口加 `@Mapper` 注解 |
| 5 | **time 类型不匹配** | `LocalDateTime` 报 `Unsupported conversion` | MySQL 的 DATETIME 对应 Java `LocalDateTime`，不要用 `java.util.Date` |
| 6 | **批量插入语法错误** | SQL 语法报错 | `<foreach>` 的 `collection="list"` 需配合 `@Param("list")` 参数 |
| 7 | **MySQL 密码不对** | 启动报 `Access denied` | application.yml 的 datasource.password 设为 `zyC191380!!` |

---

## 九、核心理解点（写完自问）

1. **`map-underscore-to-camel-case: true` 的作用是什么？什么情况下可以省略 resultMap？**
2. **`useGeneratedKeys="true" keyProperty="id"` 解决了什么问题？**
3. **为什么 `t_order_item` 要冗余存储 `product_name` 和 `price` 快照，而不是下单时实时查商品表？**
4. **`t_order_log` 表的作用是什么？它和状态机有什么关系？**
5. **为什么每个服务的 Mapper XML 的 namespace 必须与接口全限定名一致？**

---

> 教学文档版本：v1.0 | 生成日期：2026-09-15

> AI生成