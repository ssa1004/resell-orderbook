/**
 * DLQ 관리 콘솔 도메인 DTO / 값 객체 (ADR-0028).
 *
 * <p>운영자가 거래 saga (ADR-0004) 에서 떨어진 메시지를 조회 / replay / discard 할 때 쓰는
 * 비-도메인 값 객체들. {@link com.example.market.application.dlq.DlqSource} 가 출처 분류
 * (matching / settlement / refund / inspection / pg-webhook / outbox), {@link
 * com.example.market.application.dlq.DlqStats} 가 시간 / source / sku 별 집계 (market 특유의
 * bySku 차원 포함), {@link com.example.market.application.dlq.DlqBulkJob} 가 bulk 작업의
 * 진행 상태.</p>
 *
 * <p>인터페이스 / 사용:</p>
 *
 * <ul>
 *   <li>{@link com.example.market.application.port.in.DlqAdminUseCase} — 단건 조회 / 액션 / 통계</li>
 *   <li>{@link com.example.market.application.port.in.DlqBulkAdminUseCase} — 대량 처리 (dry-run / async-job)</li>
 *   <li>{@link com.example.market.application.port.out.DlqMessageStore} — Kafka DLT 또는 in-memory store 추상화</li>
 *   <li>{@link com.example.market.application.port.out.AdminRateLimiter} — admin scope 별 rate limit</li>
 *   <li>{@link com.example.market.application.port.out.DlqBulkJobRepository} — bulk job 진행률 저장소</li>
 *   <li>{@link com.example.market.application.port.out.AuditLogPort} — 운영자 액션 감사</li>
 * </ul>
 *
 * <p>패키지를 named interface 로 노출 — adapter 모듈이 본 DTO 를 직접 import 할 수 있도록
 * (Spring Modulith 의 module boundary 검증 통과).</p>
 */
@org.springframework.modulith.NamedInterface("dlq")
package com.example.market.application.dlq;
