package com.example.market.adapter.out.storage;

import com.example.market.application.port.out.PhotoStoragePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 로컬 dev 용 mock storage — 가짜 URL 만 반환. 실제 업로드/다운로드는 X.
 */
@Component
@ConditionalOnProperty(name = "market.storage.s3-enabled", havingValue = "false", matchIfMissing = true)
@Slf4j
public class LocalPhotoStorage implements PhotoStoragePort {

    @Override
    public UploadUrl issueUploadUrl(String objectKey, String contentType, Duration ttl) {
        String url = "http://localhost:8080/local-storage/upload/" + objectKey;
        log.info("[local-storage] issue upload url {} ({}s)", objectKey, ttl.getSeconds());
        return new UploadUrl(url, objectKey);
    }

    @Override
    public String issueDownloadUrl(String objectKey, Duration ttl) {
        return "http://localhost:8080/local-storage/download/" + objectKey;
    }
}
