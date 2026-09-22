-- mini-mall 电商订单系统数据库初始化脚本
-- 3 个数据库、8 张表，按微服务数据私有原则划分
-- 适用：本地环境重建 / Docker 首次启动

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
