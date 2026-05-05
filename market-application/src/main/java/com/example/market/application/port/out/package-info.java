/**
 * Outbound Ports — Repository (write), Query (read with locking), External (PG/Event/Storage).
 * 구현체는 wallet-adapter-out (JPA, Redis, Kafka, S3, RestClient) 에서.
 */
@org.springframework.modulith.NamedInterface("port-out")
package com.example.market.application.port.out;
