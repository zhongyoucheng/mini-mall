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
