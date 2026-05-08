package com.example.market.adapter.out.bulkhead;

import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * 이름으로 {@link ExternalCallBulkhead} 를 룩업. 설정에 정의된 instance 마다 1개씩.
 *
 * <p>{@link #of(String)} 호출 시점에 lazy create — properties 가 없는 인스턴스 이름은
 * {@link IllegalStateException}.</p>
 */
public final class ExternalCallBulkheads {

    private final BulkheadProperties props;
    private final ThreadPoolBulkheadRegistry registry;
    private final Map<String, ExternalCallBulkhead> created = new HashMap<>();

    public ExternalCallBulkheads(BulkheadProperties props, ThreadPoolBulkheadRegistry registry) {
        this.props = props;
        this.registry = registry;
    }

    public synchronized ExternalCallBulkhead of(String poolName) {
        ExternalCallBulkhead existing = created.get(poolName);
        if (existing != null) return existing;

        BulkheadProperties.Instance cfg = props.getInstances().get(poolName);
        if (cfg == null) {
            throw new IllegalStateException(
                    "bulkhead 인스턴스 '" + poolName + "' 가 market.bulkhead.instances 에 정의되지 않았다");
        }
        ExternalCallBulkhead created = ExternalCallBulkhead.create(registry, poolName, cfg);
        this.created.put(poolName, created);
        return created;
    }
}
