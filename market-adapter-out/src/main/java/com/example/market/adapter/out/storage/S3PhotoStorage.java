package com.example.market.adapter.out.storage;

import com.example.market.application.port.out.PhotoStoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

/**
 * AWS S3 (또는 LocalStack) 기반 검수 사진 저장. {@code market.storage.s3-enabled=true} 시 활성.
 *
 * <p>presigned URL 만 발급 — 검수자/구매자가 *직접* S3 업로드/다운로드 (서버는 stream 미경유).</p>
 */
@Component
@ConditionalOnProperty(name = "market.storage.s3-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class S3PhotoStorage implements PhotoStoragePort {

    private final S3Presigner presigner;
    private final S3Client s3Client;     // 객체 메타데이터 조회용 (선택)

    @Value("${market.storage.bucket}")
    private String bucket;

    @Override
    public UploadUrl issueUploadUrl(String objectKey, String contentType, Duration ttl) {
        var put = PutObjectRequest.builder()
                .bucket(bucket).key(objectKey).contentType(contentType).build();
        var preReq = PutObjectPresignRequest.builder()
                .signatureDuration(ttl).putObjectRequest(put).build();
        String url = presigner.presignPutObject(preReq).url().toString();
        log.debug("[s3] issued upload url bucket={} key={} ttl={}s", bucket, objectKey, ttl.getSeconds());
        return new UploadUrl(url, objectKey);
    }

    @Override
    public String issueDownloadUrl(String objectKey, Duration ttl) {
        var get = GetObjectRequest.builder().bucket(bucket).key(objectKey).build();
        var preReq = GetObjectPresignRequest.builder()
                .signatureDuration(ttl).getObjectRequest(get).build();
        return presigner.presignGetObject(preReq).url().toString();
    }
}
