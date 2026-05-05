package com.example.market.adapter.out.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "skus",
       indexes = @Index(name = "ix_sku_product", columnList = "product_id"),
       uniqueConstraints = @UniqueConstraint(name = "uk_sku_product_size_variant",
               columnNames = {"product_id", "size", "variant"}))
@Getter
@Setter
@NoArgsConstructor
public class SkuJpaEntity {

    @Id
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false, length = 30)
    private String size;

    @Column(length = 50)
    private String variant;
}
