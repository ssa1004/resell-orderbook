package com.example.market.adapter.out.persistence.jpa.repository;

import com.example.market.adapter.out.persistence.jpa.entity.ProductJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataProductRepository extends JpaRepository<ProductJpaEntity, UUID> {
    List<ProductJpaEntity> findByBrand(String brand);
}
