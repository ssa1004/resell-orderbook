package com.example.market.adapter.out.persistence.jpa.repository;

import com.example.market.adapter.out.persistence.jpa.entity.SkuJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataSkuRepository extends JpaRepository<SkuJpaEntity, UUID> {
    List<SkuJpaEntity> findByProductId(UUID productId);
}
