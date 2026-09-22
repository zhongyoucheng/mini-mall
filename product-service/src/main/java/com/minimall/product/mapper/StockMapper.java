package com.minimall.product.mapper;

import com.minimall.product.model.Stock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockMapper {

    int insert(Stock stock);

    Stock findByProductId(@Param("productId") Long productId);

    int updateQuantity(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    int updateLocked(@Param("productId") Long productId, @Param("locked") Integer locked);

    int deleteByProductId(@Param("productId") Long productId);
}
