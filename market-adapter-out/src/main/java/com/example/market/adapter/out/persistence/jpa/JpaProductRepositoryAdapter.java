package com.example.market.adapter.out.persistence.jpa;

import com.example.market.adapter.out.persistence.jpa.mapper.ProductJpaMapper;
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataProductRepository;
import com.example.market.application.port.out.ProductRepository;
import com.example.market.domain.catalog.Product;
import com.example.market.domain.catalog.ProductId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaProductRepositoryAdapter implements ProductRepository {

    private final SpringDataProductRepository jpa;

    @Override
    public void save(Product product) {
        jpa.save(ProductJpaMapper.toEntity(product));
    }

    @Override
    public Optional<Product> findById(ProductId id) {
        return jpa.findById(id.value()).map(ProductJpaMapper::toDomain);
    }

    @Override
    public List<Product> findByBrand(String brand) {
        return jpa.findByBrand(brand).stream().map(ProductJpaMapper::toDomain).toList();
    }
}
