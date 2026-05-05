package com.example.market.adapter.out.persistence.jpa;

import com.example.market.adapter.out.persistence.jpa.mapper.SkuJpaMapper;
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataSkuRepository;
import com.example.market.application.port.out.SkuRepository;
import com.example.market.domain.catalog.ProductId;
import com.example.market.domain.catalog.Sku;
import com.example.market.domain.catalog.SkuId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaSkuRepositoryAdapter implements SkuRepository {

    private final SpringDataSkuRepository jpa;

    @Override
    public void save(Sku sku) {
        jpa.save(SkuJpaMapper.toEntity(sku));
    }

    @Override
    public Optional<Sku> findById(SkuId id) {
        return jpa.findById(id.value()).map(SkuJpaMapper::toDomain);
    }

    @Override
    public List<Sku> findByProductId(ProductId productId) {
        return jpa.findByProductId(productId.value()).stream().map(SkuJpaMapper::toDomain).toList();
    }
}
