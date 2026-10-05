# 결제 기능 구현 계획 (plan.md)

> 상태: **검토 대기** (CLAUDE.md 작업 규칙에 따라 구현 전 승인 필요)
> 작성일: 2026-10-04
> 범위: 공동 장보기 방(room)의 참여자별 분담금 산정 → PG 결제 → 승인/취소/대사(reconciliation)

---

## 0. 요약

| 항목 | 결정(제안) |
|---|---|
| PG | **토스페이먼츠** 결제위젯 + 서버 승인(confirm) API |
| 돈의 흐름 | 참여자(MEMBER)가 **자기 분담금**을 결제. 방장(HOST)은 결제 대상 아님 (선결제자) |
| 금액 산정 | `room_items.actual_price` × (`alloc_quantity` / `total_qty`), 원 단위 내림, 잔액은 방장 부담 |
| 금액 확정 시점 | 방장이 "정산 시작" 시 Redis 할당을 DB로 강제 동기화 후 **스냅샷**으로 고정 |
| 정합성 | DB 상태 CAS(`UPDATE ... WHERE status = ?`) + Toss `Idempotency-Key` + 대사 스케줄러 |
| 외부 호출 | **트랜잭션 밖에서** 호출 (DB 커넥션을 PG 응답 대기 동안 점유하지 않음) |
| 신규 테이블 | `payments`, `settlement_lines` (DDL은 제안만, 실행은 승인 후) |
<!-- 
   1.방장도 결제 대상이야, 방장도 포함해서 결제해줘 
   2. 
-->

## 1. 현재 코드베이스 분석 (결제와 맞물리는 지점)

| 위치 | 현재 상태 | 결제 기능에 주는 영향 |
|---|---|---|
| `room_participants.settlement_status` | `DEFAULT 'UNPAID'`, 갱신 로직 없음 | 결제 완료 시 `PAID`로 갱신하는 데 재사용 |
| `rooms.status` | `DEFAULT 'RECRUITING'`, 전이 로직 없음 | 정산 단계(`SETTLING`) 및 완료(`COMPLETED`) 상태 추가 필요 |
| `room_items.actual_price` | `NOT NULL DEFAULT 0`, 입력 API 없음 | 방장이 실제 구매가를 입력하는 API 필요 |
| `participant_items.alloc_quantity` | **Redis(`zset:{itemId}`)가 원본**, `SyncScheduler`가 30초 주기로 DB 반영 | 금액 산정 직전 DB가 최신이 아닐 수 있음 → **강제 flush + 할당 잠금** 필수 |
| `RoomParticipantServiceImpl.allocateItem` | 방 상태 검사 없음 | 정산 시작 후에도 할당 변경 가능 → 상태 검사 추가 |
| `RoomMapper.findRoomById` | `status` 컬럼을 조회하지 않음, `RoomVO`에 `status` 필드 없음 | VO/쿼리에 `status` 추가 |
| 예외 처리 | `@RestControllerAdvice` 없음, `IllegalArgument/IllegalStateException` 사용 | 결제 오류는 응답 코드 구분이 중요 → 전역 핸들러 도입 제안 (§9) |
| `ParticipantItemMapper`, `RoomItemMapper`, `RoomParticipantMapper` | `@Param`을 `org.springframework.data.repository.query.Param`에서 import | MyBatis가 인식하지 못함(현재는 `-parameters` 컴파일 옵션 덕에 우연히 동작). 신규 Mapper는 `org.apache.ibatis.annotations.Param` 사용 |
| `application.yaml` | `optional:classpath:application-secret.yaml` import | Toss 시크릿 키는 여기에 둔다 (.gitignore 대상 확인 완료) |

---

## 2. 전체 흐름

```
[방장]                          [서버]                                  [참여자]                [Toss]
  │ 1. 실제가 입력 (PATCH price)   │                                        │                      │
  │ 2. 정산 시작 (POST settle) ───▶│ 방 상태 RECRUITING→SETTLING (CAS)       │                      │
  │                               │ Redis 할당 → DB 강제 flush              │                      │
  │                               │ 분담금 계산 → settlement_lines 스냅샷   │                      │
  │                               │                                        │                      │
  │                               │◀── 3. 결제 준비 (POST prepare) ─────────│                      │
  │                               │ payments READY 생성, orderId 발급 ─────▶│                      │
  │                               │                                        │ 4. 결제위젯 ────────▶│
  │                               │                                        │◀─ successUrl redirect│
  │                               │◀── 5. 승인 요청 (POST confirm) ─────────│ (paymentKey,orderId,amount)
  │                               │ 금액 검증 → READY→IN_PROGRESS (CAS)     │                      │
  │                               │ ── 6. /v1/payments/confirm ────────────────────────────────▶│
  │                               │ ◀─────────────────────────────────────────────────── 결과 ──│
  │                               │ DONE + participant PAID (tx)           │                      │
  │                               │ 모든 MEMBER PAID → room COMPLETED      │                      │
  │                               │                                        │                      │
  │                               │◀── 7. 웹훅 / 대사 스케줄러 (IN_PROGRESS 정리) ─────────────────│
```

### 상태 머신

**rooms.status**
```
RECRUITING ──settle──▶ SETTLING ──모든 MEMBER 결제──▶ COMPLETED
     │                    │
     └──────cancel────────┴──▶ CANCELED  (정산 전 취소 / 전원 환불 시)
```

**payments.status**
```
READY ──confirm 요청──▶ IN_PROGRESS ──Toss 성공──▶ DONE ──cancel──▶ CANCELED
  │                        │
  │                        ├──Toss 실패(명확)──▶ FAILED
  │                        └──타임아웃/5xx──▶ (IN_PROGRESS 유지, 대사 스케줄러가 확정)
  └──30분 경과──▶ EXPIRED
```

---

## 3. DB 설계 (DDL 제안 — **실행하지 않음, 승인 후 반영**)

```sql
-- rooms.status 값 확장: RECRUITING | SETTLING | COMPLETED | CANCELED (컬럼 변경 불필요)

CREATE TABLE `settlement_lines`
(
    `id`             BIGINT    NOT NULL AUTO_INCREMENT,
    `room_id`        BIGINT    NOT NULL,
    `participant_id` BIGINT    NOT NULL,
    `item_id`        BIGINT    NOT NULL,
    `alloc_quantity` INT       NOT NULL,
    `total_qty`      INT       NOT NULL,
    `item_price`     INT       NOT NULL,   -- 스냅샷 시점 actual_price
    `amount`         INT       NOT NULL,   -- 이 품목에 대한 참여자 분담금(원)
    `created_at`     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_settlement_line` (`participant_id`, `item_id`),
    KEY `IDX_settlement_room` (`room_id`),
    CONSTRAINT `FK_settle_rooms` FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`),
    CONSTRAINT `FK_settle_participants` FOREIGN KEY (`participant_id`) REFERENCES `room_participants` (`id`),
    CONSTRAINT `FK_settle_items` FOREIGN KEY (`item_id`) REFERENCES `room_items` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `payments`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `order_id`       VARCHAR(64)  NOT NULL,              -- 서버 발급 UUID (Toss orderId)
    `payment_key`    VARCHAR(200)          DEFAULT NULL, -- Toss 발급, confirm 이후 세팅
    `room_id`        BIGINT       NOT NULL,
    `participant_id` BIGINT       NOT NULL,
    `user_id`        BIGINT       NOT NULL,
    `amount`         INT          NOT NULL,
    `order_name`     VARCHAR(100) NOT NULL,
    `status`         VARCHAR(20)  NOT NULL DEFAULT 'READY',
    `method`         VARCHAR(30)           DEFAULT NULL,
    `fail_code`      VARCHAR(100)          DEFAULT NULL,
    `fail_message`   VARCHAR(500)          DEFAULT NULL,
    `approved_at`    TIMESTAMP NULL        DEFAULT NULL,
    `canceled_at`    TIMESTAMP NULL        DEFAULT NULL,
    `created_at`     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_payments_order_id` (`order_id`),
    UNIQUE KEY `UK_payments_payment_key` (`payment_key`),
    KEY `IDX_payments_participant_status` (`participant_id`, `status`),
    KEY `IDX_payments_status_updated` (`status`, `updated_at`),  -- 대사 스케줄러용
    CONSTRAINT `FK_payments_rooms` FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`),
    CONSTRAINT `FK_payments_participants` FOREIGN KEY (`participant_id`) REFERENCES `room_participants` (`id`),
    CONSTRAINT `FK_payments_users` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

설계 근거
- **금액은 `INT`(원)**: KRW는 소수점이 없고, `room_items` 가격도 `INT`. 부동소수 사용 금지.
- **`settlement_lines` 스냅샷**: 결제 후 방장이 가격/할당을 바꿔도 결제 근거가 보존됨. 분쟁 대응용 영수증 상세 화면에도 사용.
- **참여자당 활성 결제 1건**: MySQL은 partial unique index가 없어 DB 제약 대신 `prepare` 시 `SELECT ... FOR UPDATE`로 보장(§6.2).

---

## 4. 분담금 계산 규칙

```
참여자 p의 분담금 = Σ_item floor(actual_price × alloc_qty(p) / total_qty)
방장 부담금       = Σ_item actual_price − Σ_MEMBER 분담금   (내림 잔액 + 미할당분 포함)
```

- **내림 잔액은 방장이 부담**: 참여자가 1원이라도 더 내는 상황을 막는 게 분쟁이 적음.
- **미할당 수량**(`Σ alloc < total_qty`)도 방장 몫. 정산 시작 시 경고 응답(`unallocatedItems`)으로 알려준다.
- 금액 0원인 MEMBER는 결제 생략, 바로 `PAID` 처리.
- `actual_price = 0` 인 품목이 있으면 정산 시작 거부 (입력 누락 방지).
- Toss 최소 결제금액(100원) 미만이면 결제 생략 + `PAID` 처리 (검토 필요, §12).

```java
// domain/payment/service/SettlementCalculator.java — 순수 계산 로직(단위 테스트 용이)
@Component
public class SettlementCalculator {

    public List<SettlementLineVO> calculate(Long roomId,
                                            List<RoomItemVO> items,
                                            List<ParticipantItemVO> allocations) {
        Map<Long, RoomItemVO> itemById = items.stream()
                .collect(Collectors.toMap(RoomItemVO::getId, Function.identity()));

        List<SettlementLineVO> lines = new ArrayList<>();
        for (ParticipantItemVO alloc : allocations) {
            RoomItemVO item = itemById.get(alloc.getItemId());
            if (item == null || alloc.getAllocQuantity() <= 0) continue;

            // long 연산으로 오버플로 방지 후 원 단위 내림
            long amount = (long) item.getActualPrice() * alloc.getAllocQuantity() / item.getTotalQty();

            lines.add(SettlementLineVO.builder()
                    .roomId(roomId)
                    .participantId(alloc.getParticipantId())
                    .itemId(item.getId())
                    .allocQuantity(alloc.getAllocQuantity())
                    .totalQty(item.getTotalQty())
                    .itemPrice(item.getActualPrice())
                    .amount(Math.toIntExact(amount))
                    .build());
        }
        return lines;
    }
}
```

---

## 5. 패키지 구조

```
domain/payment/
├── controller/
│   ├── PaymentController.java          # prepare / confirm / fail / cancel / 조회
│   └── PaymentWebhookController.java   # Toss 웹훅 (permitAll)
├── service/
│   ├── SettlementService(.Impl).java   # 정산 시작: 잠금 → flush → 계산 → 스냅샷
│   ├── SettlementCalculator.java
│   ├── PaymentService(.Impl).java      # prepare / confirm / cancel 오케스트레이션 (트랜잭션 없음)
│   └── PaymentTxService.java           # 짧은 DB 트랜잭션 단위 모음
├── client/
│   ├── TossPaymentsClient.java         # RestClient 래퍼
│   ├── TossPaymentsProperties.java     # @ConfigurationProperties("toss.payments")
│   └── TossPaymentResponse.java / TossErrorResponse.java
├── scheduler/
│   └── PaymentReconcileScheduler.java  # IN_PROGRESS 대사, READY 만료
├── exception/
│   └── PaymentException.java, PaymentErrorCode.java
└── model/
    ├── dto/PaymentDto.java, SettlementDto.java
    ├── mapper/PaymentMapper.java, SettlementLineMapper.java
    └── vo/PaymentVO.java, SettlementLineVO.java, PaymentStatus.java
resources/mappers/payment/paymentMapper.xml, settlementLineMapper.xml
```

> `PaymentService`와 `PaymentTxService`를 분리하는 이유: 같은 클래스 내부 호출은 프록시를 타지 않아 `@Transactional`이 적용되지 않는다(self-invocation). 외부 HTTP 호출 앞뒤를 **각각 독립된 짧은 트랜잭션**으로 묶으려면 빈을 나눠야 한다.

---

## 6. 단계별 구현

### 6.1 Phase 1 — 정산 시작 (금액 확정)

**API**
| Method | Path | 권한 | 설명 |
|---|---|---|---|
| PATCH | `/api/rooms/{roomId}/items/{itemId}/price` | HOST | 실제 구매가 입력 (`RECRUITING`에서만) |
| POST | `/api/rooms/{roomId}/settlement` | HOST | 정산 시작 → 분담금 확정 |
| GET | `/api/rooms/{roomId}/settlement` | 참여자 | 내 분담금 + 품목별 상세 |

**핵심 문제: Redis 할당과의 경합**
`allocateItem`은 Redis Lua로 즉시 반영되고 DB는 최대 30초 뒤 반영된다. 정산 시작 순간에 할당이 들어오면 스냅샷과 실제가 어긋난다. 해결 순서:

1. **DB 상태 CAS**로 `RECRUITING → SETTLING` (동시에 두 번 눌러도 한 번만 성공)
2. **Redis 잠금 키** `room:locked:{roomId}` 세팅 → `alloc.lua`가 이 키를 보고 거부
3. 해당 방 품목들의 `zset:{itemId}`를 **직접 읽어 DB upsert** (스케줄러를 기다리지 않음)
4. DB에서 할당/가격을 읽어 계산 → `settlement_lines` insert

```lua
-- scripts/alloc.lua 수정: KEYS[5] = room:locked:{roomId}
if redis.call('EXISTS', KEYS[5]) == 1 then
    return -4   -- ROOM_LOCKED
end
-- (기존 로직 그대로)
```

```java
// RoomParticipantServiceImpl.allocateItem — 추가
private static final long ROOM_LOCKED = -4L;
...
List.of(dedupKey, zsetKey, totalKey, DIRTY_SET_KEY, "room:locked:" + roomId)
...
if (result == ROOM_LOCKED) {
    throw new IllegalStateException("정산이 시작되어 수량을 변경할 수 없습니다.");
}
```

> 잠금 키 TTL: CLAUDE.md 규칙상 TTL 필수 → `7일`(zset TTL과 동일). 만료 후에도 DB `rooms.status`가 `SETTLING`이므로 `allocateItem` 진입부에서 상태를 한 번 더 검사해 이중으로 막는다.

```java
// SettlementServiceImpl.java
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementServiceImpl implements SettlementService {
    private static final Duration ROOM_LOCK_TTL = Duration.ofDays(7);

    private final UserMapper userMapper;
    private final RoomMapper roomMapper;
    private final RoomItemMapper roomItemMapper;
    private final ParticipantItemMapper participantItemMapper;
    private final SettlementLineMapper settlementLineMapper;
    private final SettlementCalculator calculator;
    private final AllocationFlusher allocationFlusher; // SyncScheduler.syncItem 로직을 분리한 컴포넌트
    private final StringRedisTemplate redisTemplate;

    @Transactional
    public SettlementDto.StartResponse startSettlement(Long roomId, String email) {
        UserVO user = userMapper.findByEmail(email);
        if (user == null) throw new IllegalArgumentException("존재하지 않는 사용자입니다.");

        RoomVO room = roomMapper.findRoomById(roomId);
        if (room == null) throw new IllegalArgumentException("존재하지 않는 방입니다.");
        if (!user.getId().equals(room.getHostId())) {
            throw new IllegalStateException("방장만 정산을 시작할 수 있습니다.");
        }

        List<RoomItemVO> items = roomItemMapper.findRoomItemsByRoomId(roomId);
        validateItems(items); // actual_price > 0, total_qty > 0

        // 1) 상태 CAS — 동시 요청 중 하나만 통과
        if (roomMapper.updateStatus(roomId, "RECRUITING", "SETTLING") == 0) {
            throw new IllegalStateException("이미 정산이 시작되었거나 정산할 수 없는 방입니다.");
        }

        // 2) Redis 할당 잠금 (이후 alloc.lua가 -4 반환)
        redisTemplate.opsForValue().set("room:locked:" + roomId, "1", ROOM_LOCK_TTL);

        // 3) Redis → DB 강제 동기화 (스케줄러 주기와 무관하게 최신화)
        for (RoomItemVO item : items) {
            allocationFlusher.flush(item.getId());
        }

        // 4) 계산 + 스냅샷
        List<ParticipantItemVO> allocations = participantItemMapper.findByRoomId(roomId);
        List<SettlementLineVO> lines = calculator.calculate(roomId, items, allocations);
        if (!lines.isEmpty()) {
            settlementLineMapper.insertBatch(lines);
        }

        log.info("정산 시작: roomId={}, lines={}", roomId, lines.size());
        return SettlementDto.StartResponse.of(lines, items, allocations, room.getHostId());
    }
}
```

> **Redis 잠금은 DB 트랜잭션 롤백 대상이 아니다.** 4)에서 예외가 나 롤백되면 잠금 키가 남는다. `TransactionSynchronization.afterCompletion`에서 롤백 시 `DEL room:locked:{roomId}` 하도록 등록한다.

```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCompletion(int status) {
        if (status == STATUS_ROLLED_BACK) {
            redisTemplate.delete("room:locked:" + roomId);
        }
    }
});
```

**AllocationFlusher**: 현재 `SyncScheduler.syncItem(String)`이 private이라 재사용 불가 → `room/scheduler/AllocationFlusher`(또는 `room/service`)로 추출하고 `SyncScheduler`도 이를 호출하도록 리팩터링. 동작 변경 없음.

**추가 Mapper 메서드**
```xml
<!-- roomMapper.xml -->
<update id="updateStatus">
    UPDATE `rooms`
    SET status = #{toStatus}
    WHERE id = #{roomId}
      AND status = #{fromStatus}
</update>

<!-- participantItemMapper.xml -->
<select id="findByRoomId" resultType="com.shareCart.project.domain.room.model.vo.ParticipantItemVO">
    SELECT pi.id, pi.participant_id, pi.item_id, pi.alloc_quantity
    FROM participant_items pi
    JOIN room_items ri ON ri.id = pi.item_id
    WHERE ri.room_id = #{roomId}
</select>
```

---

### 6.2 Phase 2 — 결제 준비 (prepare)

**API**: `POST /api/rooms/{roomId}/payments/prepare` (MEMBER)

```java
// PaymentDto.java
public class PaymentDto {
    @Getter @Builder @AllArgsConstructor
    public static class PrepareResponse {
        private String orderId;
        private String orderName;    // "망원시장 공동장보기 (3개 품목)"
        private Integer amount;
        private String customerKey;  // Toss 위젯용, user id 기반 UUID (이메일 등 PII 금지)
    }

    @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor
    public static class ConfirmRequest {
        private String paymentKey;
        private String orderId;
        private Integer amount;
    }

    @Getter @Builder @AllArgsConstructor
    public static class PaymentResponse {
        private String orderId;
        private String status;
        private Integer amount;
        private String method;
        private LocalDateTime approvedAt;
    }
}
```

```java
// PaymentTxService.java
@Transactional
public PaymentVO prepare(Long roomId, Long userId) {
    RoomVO room = roomMapper.findRoomById(roomId);
    if (room == null || !"SETTLING".equals(room.getStatus())) {
        throw new PaymentException(PaymentErrorCode.ROOM_NOT_SETTLING);
    }

    // 참여자 행 잠금 → 같은 참여자의 동시 prepare 직렬화
    RoomParticipantVO participant = roomParticipantMapper.findByRoomIdAndUserIdForUpdate(roomId, userId);
    if (participant == null) throw new PaymentException(PaymentErrorCode.NOT_PARTICIPANT);
    if ("HOST".equals(participant.getRole())) throw new PaymentException(PaymentErrorCode.HOST_CANNOT_PAY);
    if ("PAID".equals(participant.getSettlementStatus())) throw new PaymentException(PaymentErrorCode.ALREADY_PAID);

    // 진행 중인 결제가 있으면 재사용 (멱등) — 금액은 스냅샷 기준이라 바뀌지 않음
    PaymentVO active = paymentMapper.findActiveByParticipantId(participant.getId()); // READY, IN_PROGRESS
    if (active != null) {
        if (PaymentStatus.IN_PROGRESS.name().equals(active.getStatus())) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_IN_PROGRESS);
        }
        return active;
    }

    int amount = settlementLineMapper.sumAmountByParticipantId(participant.getId());

    PaymentVO payment = PaymentVO.builder()
            .orderId(UUID.randomUUID().toString())
            .roomId(roomId)
            .participantId(participant.getId())
            .userId(userId)
            .amount(amount)
            .orderName(buildOrderName(room))
            .status(PaymentStatus.READY.name())
            .build();
    paymentMapper.insert(payment);
    return payment;
}
```

> **금액은 절대 클라이언트에서 받지 않는다.** 서버가 스냅샷으로 계산해 `payments.amount`에 저장하고, confirm 때 Toss redirect로 넘어온 `amount`와 비교만 한다.

---

### 6.3 Phase 3 — 결제 승인 (confirm) ★ 핵심

**API**: `POST /api/payments/confirm`

원칙
1. 금액 위변조 검증: `request.amount == payments.amount` 아니면 거부 (Toss 호출 안 함)
2. 중복 승인 방지: `READY → IN_PROGRESS` CAS. 0 rows면 다른 요청이 처리 중/완료
3. Toss 호출은 **트랜잭션 밖**에서, `Idempotency-Key: {orderId}` 헤더 포함
4. 결과 분기
   - 성공 → `DONE` + participant `PAID` + (전원 완료 시) room `COMPLETED` — 한 트랜잭션
   - Toss가 명확히 거절(4xx, 예: `REJECT_CARD_COMPANY`) → `FAILED`
   - 타임아웃 / 5xx / 네트워크 오류 → **상태를 바꾸지 않고 `IN_PROGRESS` 유지**, 클라이언트엔 "확인 중" 응답. 대사 스케줄러가 Toss 조회로 확정
   - 승인 성공했는데 DB 반영 실패 → 로그(ERROR) + `IN_PROGRESS` 유지 → 대사 스케줄러가 `DONE`으로 복구 (돈은 받았는데 기록이 없는 상황 방지)

```java
// PaymentServiceImpl.java — 트랜잭션 없음 (오케스트레이션)
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {
    private final PaymentTxService txService;
    private final TossPaymentsClient tossClient;
    private final UserMapper userMapper;

    public PaymentDto.PaymentResponse confirm(String email, PaymentDto.ConfirmRequest req) {
        UserVO user = userMapper.findByEmail(email);
        if (user == null) throw new IllegalArgumentException("존재하지 않는 사용자입니다.");

        // (tx1) 검증 + READY → IN_PROGRESS
        PaymentVO payment = txService.markInProgress(req.getOrderId(), user.getId(), req.getAmount(), req.getPaymentKey());

        TossPaymentResponse toss;
        try {
            toss = tossClient.confirm(req.getPaymentKey(), req.getOrderId(), req.getAmount());
        } catch (TossApiException e) {
            if (e.isDefinitive()) {               // 4xx 확정 실패
                txService.markFailed(payment.getOrderId(), e.getCode(), e.getMessage()); // (tx2)
                throw new PaymentException(PaymentErrorCode.PG_REJECTED, e.getMessage());
            }
            log.warn("Toss 승인 결과 불명확, 대사 대기: orderId={}", payment.getOrderId(), e);
            throw new PaymentException(PaymentErrorCode.PAYMENT_PENDING);
        } catch (ResourceAccessException e) {     // 타임아웃/연결 오류
            log.warn("Toss 승인 타임아웃, 대사 대기: orderId={}", payment.getOrderId(), e);
            throw new PaymentException(PaymentErrorCode.PAYMENT_PENDING);
        }

        // (tx3) DONE + PAID + room COMPLETED 판단
        try {
            return txService.markDone(payment.getOrderId(), toss);
        } catch (RuntimeException e) {
            // 돈은 승인됐는데 DB 반영 실패 → 반드시 추적 가능해야 함
            log.error("[PAYMENT-DB-SYNC-FAIL] 승인 완료 후 DB 반영 실패: orderId={}, paymentKey={}",
                    payment.getOrderId(), toss.getPaymentKey(), e);
            throw new PaymentException(PaymentErrorCode.PAYMENT_PENDING);
        }
    }
}
```

```java
// PaymentTxService.java
@Transactional
public PaymentVO markInProgress(String orderId, Long userId, Integer amount, String paymentKey) {
    PaymentVO payment = paymentMapper.findByOrderId(orderId);
    if (payment == null || !payment.getUserId().equals(userId)) {
        throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND); // 타인 주문은 존재 여부도 숨김
    }
    if (!payment.getAmount().equals(amount)) {
        log.warn("결제 금액 불일치: orderId={}, expected={}, actual={}", orderId, payment.getAmount(), amount);
        throw new PaymentException(PaymentErrorCode.AMOUNT_MISMATCH);
    }
    if (PaymentStatus.DONE.name().equals(payment.getStatus())) {
        throw new PaymentException(PaymentErrorCode.ALREADY_PAID);
    }
    int updated = paymentMapper.updateStatusWithKey(orderId, PaymentStatus.READY, PaymentStatus.IN_PROGRESS, paymentKey);
    if (updated == 0) {
        throw new PaymentException(PaymentErrorCode.PAYMENT_IN_PROGRESS);
    }
    return payment;
}

@Transactional
public PaymentDto.PaymentResponse markDone(String orderId, TossPaymentResponse toss) {
    int updated = paymentMapper.markDone(orderId, toss.getPaymentKey(), toss.getMethod(), toss.getApprovedAt());
    if (updated == 0) {
        // 이미 웹훅/대사에서 DONE 처리됨 → 멱등하게 현재 상태 반환
        return PaymentDto.PaymentResponse.from(paymentMapper.findByOrderId(orderId));
    }
    PaymentVO payment = paymentMapper.findByOrderId(orderId);
    roomParticipantMapper.updateSettlementStatus(payment.getParticipantId(), "PAID");

    if (roomParticipantMapper.countUnpaidMembers(payment.getRoomId()) == 0) {
        roomMapper.updateStatus(payment.getRoomId(), "SETTLING", "COMPLETED");
    }
    return PaymentDto.PaymentResponse.from(payment);
}
```

```xml
<!-- paymentMapper.xml (발췌) -->
<update id="updateStatusWithKey">
    UPDATE `payments`
    SET status = #{to}, payment_key = #{paymentKey}
    WHERE order_id = #{orderId}
      AND status = #{from}
</update>

<update id="markDone">
    UPDATE `payments`
    SET status = 'DONE',
        payment_key = #{paymentKey},
        method = #{method},
        approved_at = #{approvedAt}
    WHERE order_id = #{orderId}
      AND status IN ('READY', 'IN_PROGRESS')
</update>

<select id="findStaleInProgress" resultType="com.shareCart.project.domain.payment.model.vo.PaymentVO">
    SELECT id, order_id, payment_key, room_id, participant_id, user_id, amount, status
    FROM `payments`
    WHERE status = 'IN_PROGRESS'
      AND updated_at &lt; #{before}
    ORDER BY updated_at
    LIMIT #{limit}
</select>
```

---

### 6.4 Toss 클라이언트

```yaml
# application-secret.yaml (git 미추적)
toss:
  payments:
    secret-key: test_sk_xxxxxxxx      # 테스트 키부터 시작
    base-url: https://api.tosspayments.com
    connect-timeout: 3s
    read-timeout: 30s                 # Toss 권장: 승인 API는 응답이 늦을 수 있음
```

```java
@ConfigurationProperties(prefix = "toss.payments")
public record TossPaymentsProperties(String secretKey, String baseUrl,
                                     Duration connectTimeout, Duration readTimeout) {}
```

```java
@Slf4j
@Component
public class TossPaymentsClient {
    private final RestClient restClient;

    public TossPaymentsClient(TossPaymentsProperties props, RestClient.Builder builder) {
        String basic = Base64.getEncoder()
                .encodeToString((props.secretKey() + ":").getBytes(StandardCharsets.UTF_8));

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());

        this.restClient = builder
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                    TossErrorResponse body = readError(res);
                    throw new TossApiException(res.getStatusCode().value(), body.code(), body.message());
                })
                .build();
    }

    public TossPaymentResponse confirm(String paymentKey, String orderId, int amount) {
        return restClient.post()
                .uri("/v1/payments/confirm")
                .header("Idempotency-Key", orderId)   // 재시도해도 이중 승인 없음
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))
                .retrieve()
                .body(TossPaymentResponse.class);
    }

    public TossPaymentResponse getByOrderId(String orderId) {
        return restClient.get()
                .uri("/v1/payments/orders/{orderId}", orderId)
                .retrieve()
                .body(TossPaymentResponse.class);
    }

    public TossPaymentResponse cancel(String paymentKey, String reason, String idempotencyKey) {
        return restClient.post()
                .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cancelReason", reason))
                .retrieve()
                .body(TossPaymentResponse.class);
    }
}
```

```java
public class TossApiException extends RuntimeException {
    private final int httpStatus;
    private final String code;
    // 4xx = Toss가 명확히 거절 → FAILED 확정 가능
    // 5xx / 일부 코드(PROVIDER_ERROR, FAILED_INTERNAL_SYSTEM_PROCESSING 등) = 결과 불명확 → 대사
    public boolean isDefinitive() {
        return httpStatus >= 400 && httpStatus < 500 && !UNCERTAIN_CODES.contains(code);
    }
}
```

---

### 6.5 Phase 4 — 대사(reconciliation) 스케줄러 & 웹훅

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconcileScheduler {
    private static final Duration IN_PROGRESS_GRACE = Duration.ofMinutes(1);
    private static final Duration READY_EXPIRE = Duration.ofMinutes(30);
    private static final int BATCH_SIZE = 50;

    private final PaymentMapper paymentMapper;
    private final PaymentTxService txService;
    private final TossPaymentsClient tossClient;

    @Scheduled(fixedDelay = 60_000)
    public void reconcileInProgress() {
        LocalDateTime before = LocalDateTime.now().minus(IN_PROGRESS_GRACE);
        for (PaymentVO p : paymentMapper.findStaleInProgress(before, BATCH_SIZE)) {
            try {
                TossPaymentResponse toss = tossClient.getByOrderId(p.getOrderId());
                switch (toss.getStatus()) {
                    case "DONE" -> txService.markDone(p.getOrderId(), toss);
                    case "ABORTED", "EXPIRED" -> txService.markFailed(p.getOrderId(), toss.getStatus(), "PG 상태 동기화");
                    default -> log.info("대사 보류: orderId={}, tossStatus={}", p.getOrderId(), toss.getStatus());
                }
            } catch (TossApiException e) {
                if ("NOT_FOUND_PAYMENT".equals(e.getCode())) {
                    // Toss에 승인 시도 기록 자체가 없음 → 승인 요청이 도달하지 못함
                    txService.markFailed(p.getOrderId(), e.getCode(), "PG 승인 기록 없음");
                } else {
                    log.error("대사 실패: orderId={}", p.getOrderId(), e);
                }
            } catch (Exception e) {
                log.error("대사 실패: orderId={}", p.getOrderId(), e);
            }
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void expireReady() {
        int expired = paymentMapper.expireReadyBefore(LocalDateTime.now().minus(READY_EXPIRE));
        if (expired > 0) log.info("READY 결제 만료 처리: {}건", expired);
    }
}
```

> 다중 인스턴스 배포 시 스케줄러 중복 실행 가능. 상태 CAS 덕에 결과는 안전하지만 Toss API 호출이 중복됨 → 필요 시 Redis `SET lock:reconcile NX EX 55`로 단일 실행 보장 (기존 `SyncScheduler`와 같은 문제이므로 함께 적용 검토).

**웹훅** `POST /api/payments/webhook` (Toss 콘솔 등록, `SecurityConfig`에 `permitAll`)
- 본문(`eventType=PAYMENT_STATUS_CHANGED`, `data.orderId`)은 **신뢰하지 않고 트리거로만 사용**: `tossClient.getByOrderId()`로 재조회 후 위와 동일 분기.
- 항상 200 응답 (Toss 재전송 폭주 방지). 처리 실패는 로그 + 대사 스케줄러가 보완.
- 중복 수신 대비: `SET webhook:{transmissionId} 1 NX EX 86400`(Redis, TTL 필수 규칙 준수).

---

### 6.6 Phase 5 — 결제 취소/환불

**API**: `POST /api/payments/{orderId}/cancel` (본인 또는 HOST)

정책(제안, §12에서 확정 필요)
- `meet_at` 이전 & room `SETTLING`일 때만 본인 취소 허용
- room `COMPLETED` 이후 취소는 HOST 승인 필요(또는 운영자)

```java
public PaymentDto.PaymentResponse cancel(String email, String orderId, String reason) {
    PaymentVO payment = txService.markCanceling(orderId, userId);   // DONE → CANCELING (CAS)
    try {
        TossPaymentResponse toss = tossClient.cancel(payment.getPaymentKey(), reason, "cancel-" + orderId);
        return txService.markCanceled(orderId, toss);               // CANCELED + participant UNPAID
    } catch (TossApiException e) {
        if (e.isDefinitive()) {
            txService.revertCanceling(orderId);                     // CANCELING → DONE
            throw new PaymentException(PaymentErrorCode.CANCEL_REJECTED, e.getMessage());
        }
        throw new PaymentException(PaymentErrorCode.PAYMENT_PENDING); // 대사가 확정
    }
}
```
→ `PaymentStatus`에 `CANCELING` 추가, 대사 스케줄러가 `CANCELING`도 조회해 `CANCELED`/`DONE`으로 확정.

---

## 7. API 명세 요약

| Method | Path | 인증 | Req | Res | 주요 에러 |
|---|---|---|---|---|---|
| PATCH | `/api/rooms/{roomId}/items/{itemId}/price` | HOST | `{actualPrice}` | 200 | 403, 409(정산 중) |
| POST | `/api/rooms/{roomId}/settlement` | HOST | - | `StartResponse` | 403, 409(이미 시작), 400(가격 미입력) |
| GET | `/api/rooms/{roomId}/settlement` | 참여자 | - | 내 분담금 + 라인 | 403 |
| POST | `/api/rooms/{roomId}/payments/prepare` | MEMBER | - | `PrepareResponse` | 409(이미 결제/진행 중) |
| POST | `/api/payments/confirm` | MEMBER | `{paymentKey, orderId, amount}` | `PaymentResponse` | 400(금액 불일치), 402(PG 거절), 409, **202(확인 중)** |
| POST | `/api/payments/fail` | MEMBER | `{orderId, code, message}` | 200 | - (failUrl 기록용) |
| POST | `/api/payments/{orderId}/cancel` | 본인/HOST | `{reason}` | `PaymentResponse` | 409 |
| GET | `/api/payments/{orderId}` | 본인 | - | `PaymentResponse` | 404 |
| POST | `/api/payments/webhook` | permitAll | Toss 이벤트 | 200 | - |

> `PAYMENT_PENDING`은 **202 Accepted**로 응답 → 클라이언트는 `GET /api/payments/{orderId}`를 폴링.

---

## 8. 기존 코드 변경 목록

| 파일 | 변경 |
|---|---|
| `RoomVO` | `status` 필드 추가 |
| `roomMapper.xml` `findRoomById` | `status` 컬럼 SELECT 추가, `updateStatus` 추가 |
| `RoomMapper` | `updateStatus(roomId, from, to)` |
| `RoomItemMapper` / xml | `findRoomItemsByRoomId`, `updateActualPrice` |
| `ParticipantItemMapper` / xml | `findByRoomId` |
| `RoomParticipantMapper` / xml | `findByRoomIdAndUserIdForUpdate`, `updateSettlementStatus`, `countUnpaidMembers` |
| `RoomParticipantServiceImpl.allocateItem` | 방 상태 검사 + `ROOM_LOCKED(-4)` 처리 |
| `scripts/alloc.lua` | `KEYS[5]` 잠금 키 검사 |
| `SyncScheduler` | `syncItem` → `AllocationFlusher`로 추출 |
| `RoomServiceImpl.joinRoom` 관련 | `SETTLING` 이후 참여 차단 (`increaseParticipants`에 `AND status = 'RECRUITING'`) |
| `SecurityConfig` | `/api/payments/webhook` permitAll |
| `application.yaml` | `toss.payments.*` 비밀 아닌 값(base-url, timeout) |
| `ProjectApplication` | `@ConfigurationPropertiesScan` 추가 |
| 신규 Mapper | `@Param`은 `org.apache.ibatis.annotations.Param` 사용 |

---

## 9. 예외 처리 (제안)

결제는 클라이언트가 응답 코드로 분기해야 하므로(202 확인 중 vs 402 거절 vs 409 중복) 전용 예외 + 전역 핸들러를 둔다. 기존 `IllegalArgument/IllegalStateException`은 그대로 두되 핸들러에서 400/409로 매핑하면 기존 API에도 일관된 에러 바디가 생긴다.

```java
@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode {
    ROOM_NOT_SETTLING(HttpStatus.CONFLICT, "정산 중인 방이 아닙니다."),
    NOT_PARTICIPANT(HttpStatus.FORBIDDEN, "이 방에 참여하지 않은 사용자입니다."),
    HOST_CANNOT_PAY(HttpStatus.BAD_REQUEST, "방장은 결제 대상이 아닙니다."),
    ALREADY_PAID(HttpStatus.CONFLICT, "이미 결제가 완료되었습니다."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제 정보를 찾을 수 없습니다."),
    AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 일치하지 않습니다."),
    PAYMENT_IN_PROGRESS(HttpStatus.CONFLICT, "결제가 진행 중입니다."),
    PAYMENT_PENDING(HttpStatus.ACCEPTED, "결제 결과를 확인 중입니다."),
    PG_REJECTED(HttpStatus.PAYMENT_REQUIRED, "결제가 거절되었습니다."),
    CANCEL_REJECTED(HttpStatus.CONFLICT, "결제 취소가 거절되었습니다.");

    private final HttpStatus status;
    private final String message;
}

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<ErrorResponse> handlePayment(PaymentException e) {
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(new ErrorResponse(e.getErrorCode().name(), e.getMessage()));
    }
    // IllegalArgumentException → 400, IllegalStateException → 409
}
```

---

## 10. 보안 체크리스트

- [ ] 결제 금액은 서버 계산값만 사용, confirm 시 금액 대조
- [ ] `orderId`는 추측 불가능한 UUID, 조회/승인 시 `user_id` 소유권 확인
- [ ] 시크릿 키는 `application-secret.yaml`에만 (로그에 Authorization 헤더 출력 금지 — 현재 `org.springframework.security: DEBUG` 외 HTTP 클라이언트 로그 레벨 확인)
- [ ] `customerKey`에 이메일/전화번호 등 PII 사용 금지
- [ ] 웹훅 본문 신뢰 금지 → Toss API 재조회
- [ ] 카드번호 등 결제수단 원문은 저장하지 않음 (Toss 응답 중 `method`, 승인 시각만 저장)
- [ ] 로그에 `paymentKey`는 남기되 응답 원문 전체 덤프 금지

---

## 11. 테스트 계획

| 레벨 | 대상 | 케이스 |
|---|---|---|
| 단위 | `SettlementCalculator` | 균등 분할, 내림 잔액, 미할당 수량, 할당 0, 큰 금액(int 오버플로 방지) |
| 단위 | `PaymentServiceImpl` (Mockito, `TossPaymentsClient` mock) | 성공 / 금액 불일치 시 Toss 미호출 / 4xx → FAILED / 타임아웃 → IN_PROGRESS 유지 / 승인 성공 후 DB 실패 → PENDING |
| 단위 | `TossPaymentsClient` (`MockRestServiceServer`) | Authorization·Idempotency-Key 헤더, 에러 바디 파싱, 타임아웃 |
| 통합 | `SettlementService` (MySQL + Redis, docker-compose) | Redis에만 있는 할당이 스냅샷에 반영되는지, 정산 시작 후 `allocateItem` 거부 |
| 동시성 | confirm 중복 | 같은 orderId로 10개 스레드 동시 confirm → Toss 호출 1회, DONE 1건 (`CountDownLatch` + `ExecutorService`) |
| 동시성 | prepare 중복 | 같은 참여자 동시 prepare → READY 1건 |
| 동시성 | 정산 시작 ↔ 할당 경합 | 할당 요청 폭주 중 정산 시작 → 스냅샷 합계 == 잠금 직후 Redis 합계 |
| 통합 | 대사 스케줄러 | IN_PROGRESS + Toss DONE → DONE, NOT_FOUND → FAILED |
| 컨트롤러 | `@WebMvcTest` | 상태 코드 매핑(202/402/409), 인증 없는 요청 401, 웹훅 permitAll |

> 실제 Toss 호출은 테스트 키로 수동 E2E만 진행 (CI에서 외부 호출 금지).

---

## 12. 결정 필요 사항 (검토 요청)

1. **PG 선택**: 토스페이먼츠로 진행해도 될까요? (대안: 포트원(아임포트) — 여러 PG 추상화, 연동 코드 거의 동일)
2. **돈의 흐름**: 이 계획은 "플랫폼이 참여자 결제를 받는다"까지만 다룹니다. **방장에게 정산금을 지급(payout)** 하려면 Toss 지급대행/에스크로 계약 및 전자금융업 관련 검토가 필요합니다. MVP는
   - (A) PG 결제 후 운영자가 수동 송금, 또는
   - (B) PG 없이 "송금 완료" 체크 + 방장 확인 방식(기록만)
   중 무엇으로 할지 정해주세요.
3. **내림 잔액·미할당분을 방장 부담**으로 하는 규칙 OK?
4. **취소/환불 정책**: 언제까지, 누가 취소할 수 있는지.
5. **소액(100원 미만) 분담금** 처리: 결제 생략 후 PAID 처리 OK?
6. **DDL 반영 방식**: `schema.sql`에 추가만 할지, 별도 마이그레이션 파일(`docs/migrations/V2__payment.sql` 등)로 둘지. (승인 전 실행하지 않음)

---

## 13. 작업 순서 & 예상 PR 단위

| # | PR | 내용 | 의존 |
|---|---|---|---|
| 1 | `refactor: AllocationFlusher 추출` | `SyncScheduler.syncItem` 분리, 동작 동일 | - |
| 2 | `feat: 방 상태 전이 및 실제가 입력` | `RoomVO.status`, `updateStatus`, price API, 참여/할당 상태 검사, `alloc.lua` 잠금 | 1 |
| 3 | `feat: 정산 시작 및 분담금 스냅샷` | DDL(`settlement_lines`), Calculator, SettlementService + 테스트 | 2, DDL 승인 |
| 4 | `feat: 결제 준비/승인` | DDL(`payments`), TossClient, prepare/confirm, 예외 핸들러 + 테스트 | 3 |
| 5 | `feat: 결제 대사 스케줄러 및 웹훅` | Reconcile, webhook | 4 |
| 6 | `feat: 결제 취소` | cancel + CANCELING 대사 | 5 |

각 PR 완료 조건: `./gradlew test` 통과, 신규/변경 API의 테스트 동반.
