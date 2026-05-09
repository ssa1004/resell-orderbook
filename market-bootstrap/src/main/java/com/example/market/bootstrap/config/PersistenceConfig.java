package com.example.market.bootstrap.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@Configuration
@EntityScan(basePackages = {
        "com.example.market.adapter.out.persistence.jpa.entity",
        "com.example.market.adapter.out.persistence.outbox",
        "com.example.market.adapter.out.persistence.compensation",
})
@EnableJpaRepositories(basePackages = {
        "com.example.market.adapter.out.persistence.jpa.repository",
        "com.example.market.adapter.out.persistence.outbox",
        "com.example.market.adapter.out.persistence.compensation",
})
public class PersistenceConfig {
}
