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

    /**
     * 按预期当前状态更新订单状态（乐观锁）
     * @return 影响行数，0 表示实际状态与预期不符，未更新
     */
    int updateStatusWhen(@Param("id") Long id,
                         @Param("status") Integer status,
                         @Param("expectedStatus") Integer expectedStatus);

    /** 查询超时未支付订单（status=0 且创建时间早于指定分钟前） */
    List<Order> findTimeoutOrders(@Param("minutes") int minutes);
}
