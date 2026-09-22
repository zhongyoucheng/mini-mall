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
