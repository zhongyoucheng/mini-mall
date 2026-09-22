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
