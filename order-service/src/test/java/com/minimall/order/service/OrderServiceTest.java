package com.minimall.order.service;

import com.minimall.order.client.ProductClient;
import com.minimall.order.common.BusinessException;
import com.minimall.order.config.RabbitConfig;
import com.minimall.order.dto.OrderMessage;
import com.minimall.order.mapper.OrderItemMapper;
import com.minimall.order.mapper.OrderLogMapper;
import com.minimall.order.mapper.OrderMapper;
import com.minimall.order.model.Order;
import com.minimall.order.model.OrderLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService 单元测试")
class OrderServiceTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private OrderLogMapper orderLogMapper;
    @Mock
    private ProductClient productClient;
    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private OrderService orderService;

    @Test
    @DisplayName("支付订单：status=0 时应成功更新为 1")
    void payOrder_status0_shouldUpdateTo1() {
        Order order = new Order();
        order.setId(1L);
        order.setStatus(0);

        when(orderMapper.findById(1L)).thenReturn(order);
        when(orderMapper.updateStatusWhen(1L, 1, 0)).thenReturn(1);

        orderService.payOrder(1L);

        verify(orderMapper).updateStatusWhen(1L, 1, 0);
        verify(orderLogMapper).insert(any(OrderLog.class));
    }

    @Test
    @DisplayName("支付订单：status=1 时应抛出异常")
    void payOrder_status1_shouldThrow() {
        Order order = new Order();
        order.setId(1L);
        order.setStatus(1);

        when(orderMapper.findById(1L)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            orderService.payOrder(1L);
        });

        assertEquals(400, ex.getCode());
        verify(orderMapper, never()).updateStatusWhen(anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("取消订单：status=0 时应成功更新为 2")
    void cancelOrder_status0_shouldUpdateTo2() {
        Order order = new Order();
        order.setId(1L);
        order.setStatus(0);

        when(orderMapper.findById(1L)).thenReturn(order);
        when(orderMapper.updateStatusWhen(1L, 2, 0)).thenReturn(1);

        orderService.cancelOrder(1L);

        verify(orderMapper).updateStatusWhen(1L, 2, 0);
        verify(orderLogMapper).insert(any(OrderLog.class));
    }

    @Test
    @DisplayName("取消订单：status=2 时应抛出异常")
    void cancelOrder_status2_shouldThrow() {
        Order order = new Order();
        order.setId(1L);
        order.setStatus(2);

        when(orderMapper.findById(1L)).thenReturn(order);

        assertThrows(BusinessException.class, () -> {
            orderService.cancelOrder(1L);
        });

        verify(orderMapper, never()).updateStatusWhen(anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("支付订单：订单不存在应抛出 404")
    void payOrder_notFound_shouldThrow404() {
        when(orderMapper.findById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            orderService.payOrder(999L);
        });

        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("超时取消：应更新 status=3 + 写日志 + 发 MQ")
    void timeoutCancelOrder_shouldUpdateAndLogAndSendMQ() {
        Order order = new Order();
        order.setId(1L);
        order.setOrderNo("NO001");
        order.setUserId(1L);
        order.setTotalAmount(BigDecimal.valueOf(100));
        order.setStatus(0);

        when(orderMapper.findById(1L)).thenReturn(order);
        when(orderMapper.updateStatusWhen(1L, 3, 0)).thenReturn(1);

        orderService.timeoutCancelOrder(order);

        verify(orderMapper).updateStatusWhen(1L, 3, 0);
        verify(orderLogMapper).insert(argThat(log ->
                "TIMEOUT".equals(log.getAction()) &&
                        log.getToStatus() == 3
        ));
        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.ROUTING_KEY), any(OrderMessage.class));
    }
}
