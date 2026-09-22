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
