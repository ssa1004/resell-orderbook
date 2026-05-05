package com.example.market.adapter.out.persistence.jpa.entity;

import com.example.market.domain.catalog.ProductCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "products", indexes = {
        @Index(name = "ix_product_brand", columnList = "brand"),
        @Index(name = "ix_product_style_code", columnList = "style_code")
})
@Getter
@Setter
@NoArgsConstructor
public class ProductJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 80)
    private String brand;

    @Column(name = "model_name", nullable = false, length = 200)
    private String modelName;

    @Column(name = "style_code", length = 50)
    private String styleCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProductCategory category;

    @Column(name = "release_date")
    private Instant releaseDate;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
