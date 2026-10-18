# 정산(계좌이체) 기능 구현 계획 (plan.md)

> 상태: **검토 대기** (CLAUDE.md 작업 규칙에 따라 구현 전 승인 필요)
> 작성일: 2026-10-04 / 개정: 2026-10-18 (v7, MVP 범위 확정)
> 범위: 공동 장보기 방(room)에서 할당 마감 → 방장 장보기 → 실제 금액 입력 → 참여자별 분담금 산정 → 방장 계좌로 송금 → 방장 입금 확인

### 변경 이력

| 버전 | 내용 |
|---|---|
| v7 | **MVP만 구현**하고 미룬 기능은 §12 "2차 구현"에 정리(계좌 재입력 API 등). 예외 핸들러는 **정산 컨트롤러 전용**으로 축소해 기존 API는 건드리지 않음. |
| v6 | 결정 사항 반영. ① **장보는 동안 할당 금지**: `SHOPPING` 상태를 추가하고, 할당 잠금을 "장보기 시작" 시점으로 앞당김. ② 계좌 Redis TTL 7일 확정. ③ 원 단위 나머지는 방장 부담 확정. ④ **할당 0개 MEMBER는 허용하지 않음**: 장보기 시작 시 차단. ⑤ `COMPLETED`는 되돌릴 수 없음 확정. ⑥ 전역 예외 핸들러 추가 확정(기존 API 포함). |
| v5 | 장을 먼저 보고 실제 금액으로 정산. 정산 취소 추가. 계좌는 Redis에 저장해 DDL 없음. 정산 중 방 나가기 금지. |
| v4 | 토스(PG)를 제거하고 계좌이체 + 방장 입금 확인 방식으로 바꿈. |
| v3 | 방장 분담금은 표시만 하고 MEMBER만 낸다. |
| v2 | 미할당 수량이 있으면 정산을 막는다. |

---

## 0. 요약

| 항목 | 결정 |
|---|---|
| 순서 | 모집·할당(`RECRUITING`) → **장보기 시작**(할당 잠금, `SHOPPING`) → 방장 장보기 → 실제 금액·수량 입력 → **정산 시작**(`SETTLING`) → MEMBER 이체 → 방장 입금 확인 → `COMPLETED` |
| 할당 변경 | `RECRUITING`에서만 가능. **장보기 시작 이후에는 불가** |
| 장보기 시작 조건 | 모든 품목 수량 = 할당 합계 + **MEMBER마다 전체 품목 중 최소 1개 품목은 할당**(일부 품목을 안 담는 것은 허용) |
| 정산 시작 조건 | 모든 품목 실제가 입력 + 모든 품목 수량 = 할당 합계(덜 산 경우 수량을 줄였다면 다시 확인) |
| 돈의 흐름 | 방장이 마트에서 전액 결제. MEMBER는 **방장 계좌로 직접 이체**(앱 밖). 앱은 금액 안내와 입금 확인 기록만 한다. |
| 금액 산정 | `room_items.actual_price`(실제 구매가) × (`alloc_quantity` / `total_qty`), 원 단위 내림, **나머지는 방장 부담** |
| 입금 확인 | 방장이 MEMBER별로 `PAID` 체크(정산 중에는 해제 가능). MEMBER 전원 `PAID`이면 `COMPLETED`. **`COMPLETED`는 되돌릴 수 없다.** |
| 방장 계좌 | 정산 시작 때 방장이 입력하고 **Redis에 TTL 7일**로 저장. `COMPLETED`/정산 취소 시 삭제 |
| 예외 처리 | 정산 컨트롤러 전용 `@RestControllerAdvice`. **기존 API는 건드리지 않음**(§7) |
| 범위 | **MVP만** 구현. 나머지는 §12 "2차 구현" |
| DB 변경 | **없음** (`rooms.status` 값만 늘어나며 컬럼 변경 불필요) |

**만들지 않는 것**: PG 결제. 그 밖에 미룬 기능은 §12 "2차 구현"에 정리.

**MVP에 남긴 것 중 빼지 않은 이유**: "할당 다시 열기"와 "정산 취소"는 각각 상태 전환 하나짜리라 작다. 빼면 품절(덜 산 경우)이나 실제가 오타가 났을 때 **틀린 금액을 고칠 방법이 없어** 남겼다.

---

## 1. 현재 코드베이스 분석 (정산과 맞물리는 지점)

| 위치 | 현재 상태 | 정산 기능에 주는 영향 |
|---|---|---|
| `room_participants.settlement_status` | `DEFAULT 'UNPAID'`, 갱신 로직 없음 | 방장 입금 확인 시 `PAID`로 갱신하는 데 그대로 재사용 |
| `rooms.status` | `DEFAULT 'RECRUITING'`, 전이 로직 없음 | `SHOPPING`, `SETTLING`, `COMPLETED` 전이 추가 |
| `room_items.actual_price` | `NOT NULL DEFAULT 0`, 입력 API 없음 | 기존 품목 수정 API에 `actualPrice` 추가 |
| `PATCH /api/rooms/{roomId}/items/{roomItemId}` (`RoomItemServiceImpl.updateItemDetails`) | 방장 권한 검사 있음. `unit`, `stepQty`, `totalQty` 수정. 방 상태 검사 없음 | 실제가 입력과 덜 산 경우 수량 수정에 재사용. 상태 검사 추가 |
| `participant_items.alloc_quantity` | **Redis(`zset:{itemId}`)가 원본**, `SyncScheduler`가 30초 주기로 DB 반영 | 장보기 시작 시 DB가 최신이 아닐 수 있음 → **강제 flush + 할당 잠금** 필수 |
| `RoomParticipantServiceImpl.allocateItem` | 방 상태 검사 없음 | `RECRUITING` 외에는 할당 차단 |
| `RoomMapper.findRoomById` | `status`를 조회하지 않음, `RoomVO`에 `status` 없음 | VO와 쿼리에 `status` 추가 |
| 방 나가기 / 강퇴 API | **없음** | 만들 때 상태 조건 포함(§6.8). 할당 0개 MEMBER 해소 수단이 "할당하기"뿐인 점 참고 |
| 예외 처리 | `@RestControllerAdvice` 없음, `IllegalArgument/IllegalStateException` 사용 | 정산 컨트롤러 전용 핸들러만 추가(§7). 기존 API는 그대로 |
| `ParticipantItemMapper`, `RoomItemMapper`, `RoomParticipantMapper` | `@Param`을 `org.springframework.data.repository.query.Param`에서 import | 신규 Mapper 메서드는 `org.apache.ibatis.annotations.Param` 사용 |

---

## 2. 전체 흐름

```
[방장]                                    [서버]                                  [MEMBER]
  │                                         │◀────────── 할당 (기존 allocate) ─────────│
  │ 1. 장보기 시작 (POST shopping) ─────────▶│ RECRUITING→SHOPPING (CAS)               │
  │                                         │ 할당 잠금 → Redis→DB flush               │
  │                                         │ 검증(수량=할당, 0개 MEMBER 없음) 실패 시 롤백│
  │ 2. 장보기 (앱 밖, 마트에서 전액 결제)     │                                        │
  │ 3. 실제가·수량 입력 (기존 PATCH item) ──▶│ SHOPPING에서 허용                        │
  │ 4. 정산 시작 + 계좌 입력 (POST) ────────▶│ SHOPPING→SETTLING (CAS)                 │
  │                                         │ 검증(실제가, 수량=할당) 실패 시 롤백       │
  │                                         │ 계좌 → Redis (TTL 7일)                   │
  │                                         │── 5. 내 분담금 + 방장 계좌 (GET) ───────▶│
  │                                         │                                        │ 6. 은행 앱으로 이체 (앱 밖)
  │ 7. 입금 확인 체크 (PATCH, MEMBER별) ────▶│ UNPAID→PAID                             │
  │                                         │ MEMBER 전원 PAID → COMPLETED, 계좌 삭제  │
```

### 상태 머신

**rooms.status**
```
            장보기 시작               정산 시작                 MEMBER 전원 PAID
RECRUITING ───────────▶ SHOPPING ───────────▶ SETTLING ─────────────────▶ COMPLETED (최종)
     ▲                    │  ▲                   │
     └──할당 다시 열기────┘  └──정산 취소(PAID 없음)┘
(각 전이의 검증이 실패하면 롤백되어 이전 상태 유지)
```

**room_participants.settlement_status** (MEMBER만 의미 있음)
```
UNPAID ◀──방장 체크/해제──▶ PAID     (SETTLING 동안만 변경 가능, COMPLETED 이후 고정)
```

| 상태 | 할당 | 품목 수정(가격·수량) | 방 참여 | 방 나가기 |
|---|---|---|---|---|
| `RECRUITING` | 가능 | 가능 | 가능 | 가능 |
| `SHOPPING` | **불가** | 가능(방장, 실제가·덜 산 수량 입력) | 불가 | 불가 |
| `SETTLING` | 불가 | 불가 | 불가 | 불가 |
| `COMPLETED` | 불가 | 불가 | 불가 | 가능 |

---

## 3. 데이터 저장

**DB 변경 없음.** `rooms.status`에 `SHOPPING`, `SETTLING`, `COMPLETED` 값만 새로 쓴다(VARCHAR라 DDL 불필요). `schema.sql`은 변경하지 않는다.

| 데이터 | 저장 위치 | 이유 |
|---|---|---|
| 분담금 | 저장 안 함. 조회 시 계산 | `SETTLING` 이후에는 할당과 품목 수정이 모두 막혀 입력이 고정됨 → 항상 같은 결과. 정산 취소 후에는 가격이 바뀔 수 있지만, 재정산 시 다시 계산하면 됨 |
| 방장 계좌 | Redis `room:account:{roomId}` (Hash: `bankName`, `accountNumber`, `accountHolder`), **TTL 7일** | 방마다 입력하는 일회성 정보 → DB에 개인정보를 남기지 않음. `COMPLETED`/정산 취소 시 즉시 삭제 |

- shortcut: 계좌 키가 TTL 만료나 Redis 장애로 사라지면 조회 시 계좌가 비어 있다. MVP에서는 방장이 MEMBER에게 직접 알려준다. 재입력 API나 DB 저장은 2차(§12).
- **금액은 `INT`(원)**: KRW는 소수점이 없다. 부동소수는 사용하지 않는다.

---

## 4. 분담금 계산 규칙

```
참여자 p의 품목별 기본 금액 = floor(actual_price × alloc_qty(p) / total_qty)
품목별 나머지              = actual_price − Σ_p 기본 금액      (0 ≤ 나머지 < 할당자 수)
방장 품목별 금액           = 방장 기본 금액 + 품목별 나머지
참여자 p의 분담금          = Σ_item 품목별 금액(p)
```
- 미할당이 없으므로 **Σ 전 참여자 분담금 == Σ actual_price**가 항상 성립한다(단위 테스트로 검증).
- MEMBER 분담금만 송금 대상이다. 방장 분담금은 "내 몫"으로 표시만 한다. 방장이 받을 금액은 Σ MEMBER 분담금이다.
- **나머지는 방장 부담**(확정): MEMBER가 1원도 더 내지 않고, 결과가 결정적이다. 방장이 그 품목을 할당받지 않았어도 방장 몫에 더한다.

**차단 조건** (해당하면 거부하고, 상태는 이전 그대로)

| 시점 | 조건 | 에러 코드 | 응답 상세 | 해결 |
|---|---|---|---|---|
| 장보기 시작 | `Σ alloc ≠ total_qty` 품목 | `UNALLOCATED_ITEMS` | 품목별 `totalQty`, `allocatedQty` | 남은 수량을 할당하거나, 방장이 품목 수량을 할당량에 맞게 수정 |
| 장보기 시작 | **모든 품목**에서 할당이 0인 MEMBER (일부 품목만 0인 것은 통과) | `MEMBER_WITHOUT_ITEMS` | 참여자 id/닉네임 | 해당 MEMBER가 아무 품목이나 1개 이상 할당 |
| 정산 시작 | `actual_price = 0` 품목 | `PRICE_NOT_SET` | 품목 id/이름 | 방장이 실제가 입력 |
| 정산 시작 | `Σ alloc ≠ total_qty` 품목 (덜 사서 수량을 줄인 경우) | `UNALLOCATED_ITEMS` | 품목별 `totalQty`, `allocatedQty` | 아래 "덜 산 경우" 참고 |

**덜 산 경우 (품절 등)**
- 방장이 `SHOPPING`에서 기존 품목 수정 API로 `totalQty`를 실제 구매 수량으로 줄이고 실제가를 입력한다.
- 그러면 할당 합계가 수량보다 많아져 정산 시작이 거부된다. 할당은 잠겨 있으므로, 방장이 **할당 다시 열기**(`SHOPPING → RECRUITING`)를 하고 MEMBER가 수량을 줄인 뒤 다시 장보기 시작 → 정산 시작을 한다.
  - shortcut: 다시 열면 모든 MEMBER가 할당을 바꿀 수 있다. 덜 사는 일이 잦으면 "방장이 할당을 직접 조정하는 API"를 추가한다.
- 가격과 수량은 **같은 구매량 기준**이어야 한다(분담 단가 = `actual_price / total_qty`). 실제가만 줄이고 수량을 그대로 두면 MEMBER가 덜 내고 방장이 손해 본다. 품목 수정 화면에서 "실제 구매 수량·금액"을 함께 입력받도록 안내한다.

**아무 품목도 안 담은 MEMBER**: 판정 기준은 "MEMBER별 전체 품목 할당 합계 = 0"이다. 특정 품목을 안 담는 것은 정상이고, 전체 품목 중 하나라도 담으면 통과한다. 장보기 시작에서 막으므로 정산 단계에는 존재하지 않는다. 다만 지금은 방 나가기와 강퇴 API가 없어서 해소 방법이 "해당 MEMBER가 할당하기" 하나뿐이다.

```java
// domain/settlement/service/SettlementCalculator.java: 순수 계산(단위 테스트 용이)
@Component
public class SettlementCalculator {

    public SettlementResult calculate(Long hostParticipantId,
                                      List<Long> participantIds,       // HOST 포함
                                      List<RoomItemVO> items,
                                      List<ParticipantItemVO> allocations) {
        Map<Long, List<ParticipantItemVO>> allocByItem = allocations.stream()
                .filter(a -> a.getAllocQuantity() > 0)
                .collect(Collectors.groupingBy(ParticipantItemVO::getItemId));

        List<SettlementLine> lines = new ArrayList<>();
        List<UnallocatedItem> unallocated = new ArrayList<>();

        for (RoomItemVO item : items) {
            List<ParticipantItemVO> allocs = allocByItem.getOrDefault(item.getId(), List.of());
            int allocated = allocs.stream().mapToInt(ParticipantItemVO::getAllocQuantity).sum();
            if (allocated != item.getTotalQty()) {
                unallocated.add(new UnallocatedItem(item.getId(), item.getTotalQty(), allocated));
                continue;
            }

            long floorSum = 0;
            for (ParticipantItemVO a : allocs) {
                long amount = (long) item.getActualPrice() * a.getAllocQuantity() / item.getTotalQty(); // long: 오버플로 방지
                floorSum += amount;
                lines.add(new SettlementLine(a.getParticipantId(), item.getId(), a.getAllocQuantity(), Math.toIntExact(amount), 0));
            }
            int remainder = Math.toIntExact(item.getActualPrice() - floorSum);
            if (remainder > 0) {
                lines.add(new SettlementLine(hostParticipantId, item.getId(), 0, remainder, remainder)); // 나머지는 방장 부담
            }
        }

        Map<Long, Integer> totals = participantIds.stream().collect(Collectors.toMap(Function.identity(), id -> 0));
        lines.forEach(l -> totals.merge(l.participantId(), l.amount(), Integer::sum));
        return new SettlementResult(lines, totals, unallocated);
    }
}

// DB 테이블과 매핑되지 않으므로 VO가 아닌 DTO(record)
public record SettlementLine(Long participantId, Long itemId, int allocQuantity, int amount, int roundingAdjustment) {}
```
> 장보기 시작 검증은 가격 없이 수량만 보면 되므로 `unallocated`만 사용한다. 할당 0개 MEMBER는 `allocations`에서 MEMBER별 합계로 따로 검사한다.

---

## 5. 패키지 구조

```
domain/settlement/
├── controller/SettlementController.java     # 장보기 시작/할당 다시 열기, 정산 시작/조회/입금 확인/취소
├── service/
│   ├── SettlementService(.Impl).java
│   └── SettlementCalculator.java
├── exception/
│   ├── SettlementValidationException.java   # 에러 코드 + 상세 목록
│   └── SettlementExceptionHandler.java      # SettlementController 전용
└── model/dto/SettlementDto.java             # AccountRequest, SettlementResponse, SettlementLine, ...
```
신규 Mapper 없음. 기존 `RoomMapper`, `RoomItemMapper`, `ParticipantItemMapper`, `RoomParticipantMapper`에 메서드만 추가(§8).

---

## 6. 단계별 구현

### 6.1 API

| Method | Path | 권한 | Req | Res | 주요 에러 |
|---|---|---|---|---|---|
| PATCH | `/api/rooms/{roomId}/items/{roomItemId}` (**기존**) | HOST | 기존 `{unit, stepQty, totalQty}` + `actualPrice` | 200 | 403, 409(`RECRUITING`/`SHOPPING` 아님) |
| POST | `/api/rooms/{roomId}/shopping` | HOST | - | 200 | 403, 409(`RECRUITING` 아님), 400(`UNALLOCATED_ITEMS` / `MEMBER_WITHOUT_ITEMS` + 상세) |
| DELETE | `/api/rooms/{roomId}/shopping` | HOST | - | 200 | 403, 409(`SHOPPING` 아님) |
| POST | `/api/rooms/{roomId}/settlement` | HOST | `{bankName, accountNumber, accountHolder}` | `SettlementResponse` | 403, 409(`SHOPPING` 아님), 400(`PRICE_NOT_SET` / `UNALLOCATED_ITEMS` + 상세) |
| GET | `/api/rooms/{roomId}/settlement` | 참여자 | - | MEMBER: 내 분담금 + 품목별 내역 + 방장 계좌 + 내 상태 / HOST: 내 몫 + MEMBER별 금액·상태 + 받을 총액 | 403, 409(정산 전) |
| PATCH | `/api/rooms/{roomId}/participants/{participantId}/settlement-status` | HOST | `{status: "PAID" \| "UNPAID"}` | 200 | 403, 409(`SETTLING` 아님), 400(HOST 대상) |
| DELETE | `/api/rooms/{roomId}/settlement` | HOST | - | 200 | 403, 409(`SETTLING` 아님 / `PAID` MEMBER 있음) |

### 6.2 기존 품목 수정 API 보강 (`RoomItemServiceImpl.updateItemDetails`)
- 방 상태가 `RECRUITING` 또는 `SHOPPING`이 아니면 거부(정산 중 가격·수량 변경 차단 → 조회 시 계산 결과 고정)
- `actualPrice`는 0 이상 정수(0 = 미입력)
- 기존 `updateItemDetails` 쿼리에 `actual_price = #{actualPrice}` 추가
- `alloc.lua`는 매 호출마다 DB `totalQty`를 cap으로 받으므로 수량을 바꿔도 Redis 쪽은 따로 갱신하지 않아도 된다.

### 6.3 장보기 시작: 할당 잠금 (Redis 경합 처리)

`allocateItem`은 Redis Lua로 즉시 반영되고 DB 반영은 최대 30초 늦다. 장보기 시작 순간의 할당을 정확히 고정하는 순서는 다음과 같다.

1. **DB 상태 CAS** `RECRUITING → SHOPPING` (동시 요청 중 하나만 통과)
2. **Redis 잠금 키** `room:locked:{roomId}` 세팅 → `alloc.lua`가 거부
3. 방 품목들의 `zset:{itemId}`를 **직접 읽어 DB upsert** (스케줄러를 기다리지 않음)
4. 검증(수량 = 할당, 할당 0개 MEMBER 없음). 실패하면 예외 → 롤백(`RECRUITING` 복귀) + 롤백 훅에서 잠금 해제

```lua
-- scripts/alloc.lua 수정: KEYS[5] = room:locked:{roomId}
if redis.call('EXISTS', KEYS[5]) == 1 then
    return -4   -- ROOM_LOCKED
end
-- (기존 로직 그대로)
```

```java
// RoomParticipantServiceImpl.allocateItem: 추가
private static final long ROOM_LOCKED = -4L;
...
// 진입부: room.status가 RECRUITING이 아니면 거부 (잠금 키 TTL 만료 대비 이중 차단)
List.of(dedupKey, zsetKey, totalKey, DIRTY_SET_KEY, "room:locked:" + roomId)
...
if (result == ROOM_LOCKED) {
    throw new IllegalStateException("장보기가 시작되어 수량을 변경할 수 없습니다.");
}
```

```java
// SettlementServiceImpl.startShopping
@Transactional
public void startShopping(Long roomId, String email) {
    getRoomAsHost(roomId, email); // 사용자·방 존재, 방장 여부 확인
    List<RoomItemVO> items = roomItemMapper.findRoomItemsByRoomId(roomId);

    if (roomMapper.updateStatus(roomId, "RECRUITING", "SHOPPING") == 0) {
        throw new IllegalStateException("모집 중인 방이 아닙니다.");
    }

    registerLockRollback(roomId, items); // 롤백 시 잠금 해제 + dirty set 재등록
    redisTemplate.opsForValue().set("room:locked:" + roomId, "1", ROOM_LOCK_TTL);

    items.forEach(item -> allocationFlusher.flush(item.getId()));

    validateAllocations(roomId, items); // UNALLOCATED_ITEMS, MEMBER_WITHOUT_ITEMS
    log.info("장보기 시작: roomId={}", roomId);
}
```

> **Redis 작업은 DB 롤백 대상이 아니다.** `TransactionSynchronization.afterCompletion(ROLLED_BACK)`에서 `DEL room:locked:{roomId}`를 실행하고, flush한 품목을 dirty set에 다시 넣는다(flush의 DB upsert도 롤백되므로 스케줄러가 다시 동기화하게).
>
> **잠금 키 TTL 7일**(CLAUDE.md 규칙상 TTL 필수, zset TTL과 동일). 만료돼도 `allocateItem` 진입부의 상태 검사로 이중 차단한다.
>
> 검증 실패는 정상 흐름이므로 `INFO`로 로깅한다.

**할당 다시 열기** (`DELETE .../shopping`, `SHOPPING → RECRUITING`): 덜 사서 할당을 줄여야 할 때 쓴다. CAS 성공 후 커밋되면 `DEL room:locked:{roomId}`.

**AllocationFlusher**: 현재 `SyncScheduler.syncItem(String)`이 private이라 재사용할 수 없다 → 컴포넌트로 추출하고 `SyncScheduler`도 이를 호출하게 리팩터링한다. 동작 변경 없음.

### 6.4 정산 시작
할당은 장보기 시작 때 이미 DB에 고정됐으므로 flush나 잠금은 필요 없다.

```java
@Transactional
public SettlementDto.SettlementResponse startSettlement(Long roomId, String email, SettlementDto.AccountRequest account) {
    getRoomAsHost(roomId, email);
    if (roomMapper.updateStatus(roomId, "SHOPPING", "SETTLING") == 0) {
        throw new IllegalStateException("장보기 중인 방이 아닙니다.");
    }
    SettlementResult result = calculate(roomId);
    validate(result); // PRICE_NOT_SET, UNALLOCATED_ITEMS → 예외 → 롤백(SHOPPING 유지)

    afterCommit(() -> saveAccount(roomId, account)); // Redis Hash + TTL 7일. 롤백 시 저장 안 됨
    log.info("정산 시작: roomId={}", roomId);           // 계좌번호는 로그에 남기지 않음
    return SettlementDto.SettlementResponse.forHost(result, account);
}
```

### 6.5 정산 조회
- 참여자 확인 → 방 상태가 `SETTLING`/`COMPLETED`가 아니면 409
- DB에서 품목·할당을 읽어 `SettlementCalculator`로 계산(입력이 고정되어 결과가 항상 같음)
- MEMBER에게는 본인 금액, 내역, 방장 계좌, 본인 상태를 준다. HOST에게는 내 몫, MEMBER별 금액·상태, 받을 총액을 준다.
- 방장 계좌는 **해당 방 참여자에게만**, `SETTLING` 동안만 반환한다. Redis에 없으면 `null`(MVP: "방장에게 계좌를 문의하세요" 안내).

### 6.6 입금 확인 (방장)
```java
@Transactional
public void updateSettlementStatus(Long roomId, Long participantId, String email, String status) {
    // 방장 확인, 상태 값 PAID/UNPAID만 허용
    // 방 SETTLING 아니면 409 → COMPLETED 이후에는 체크 변경 불가(되돌릴 수 없음)
    // 대상이 이 방의 MEMBER인지 확인 (HOST면 400)
    roomParticipantMapper.updateSettlementStatus(participantId, status);
    if ("PAID".equals(status) && roomParticipantMapper.countMembersByStatus(roomId, "UNPAID") == 0) {
        if (roomMapper.updateStatus(roomId, "SETTLING", "COMPLETED") == 1) { // CAS라 동시 요청에도 1회
            afterCommit(() -> redisTemplate.delete(List.of("room:account:" + roomId, "room:locked:" + roomId)));
        }
    }
}
```
> `COMPLETED` 이후에는 `allocateItem`과 품목 수정이 상태 검사로 막히므로 잠금 키도 함께 지운다.

### 6.7 정산 취소 (방장)
잘못 입력한 실제가를 고치기 위해 `SETTLING → SHOPPING`으로 되돌린다. 할당 잠금은 유지된다.

```java
@Transactional
public void cancelSettlement(Long roomId, String email) {
    getRoomAsHost(roomId, email);
    if (roomParticipantMapper.countMembersByStatus(roomId, "PAID") > 0) {
        throw new IllegalStateException("입금 확인된 참여자가 있어 정산을 취소할 수 없습니다.");
    }
    if (roomMapper.updateStatus(roomId, "SETTLING", "SHOPPING") == 0) {
        throw new IllegalStateException("정산 중인 방이 아닙니다.");
    }
    afterCommit(() -> redisTemplate.delete("room:account:" + roomId));
}
```
- 방장이 아직 체크하지 않았을 뿐 이미 이체한 MEMBER가 있을 수 있다. 취소 화면에서 "이미 이체한 참여자와 따로 정리하세요"라고 안내한다.
- shortcut: `PAID` 확인과 상태 변경 사이에 방장이 체크하면 경합이 생긴다. 두 요청 모두 방장 본인이 보내는 것이라 드물다. 문제가 되면 `updateStatus` 조건에 `NOT EXISTS (PAID MEMBER)`를 넣는다.

### 6.8 방 참여·나가기 제약
- 참여: `RECRUITING`에서만 (`increaseParticipants`에 `AND status = 'RECRUITING'`)
- 나가기: 현재 API가 **없다**. 이번 범위에서 만들지 않는다. 만들 때는 `SHOPPING`/`SETTLING`이면 거부하고 `RECRUITING`/`COMPLETED`이면 허용한다.

### 6.9 추가 Mapper 메서드

```xml
<!-- roomMapper.xml -->
<update id="updateStatus">
    UPDATE `rooms` SET status = #{toStatus}
    WHERE id = #{roomId} AND status = #{fromStatus}
</update>

<!-- participantItemMapper.xml -->
<select id="findByRoomId" resultType="com.shareCart.project.domain.room.model.vo.ParticipantItemVO">
    SELECT pi.id, pi.participant_id, pi.item_id, pi.alloc_quantity
    FROM participant_items pi
    JOIN room_items ri ON ri.id = pi.item_id
    WHERE ri.room_id = #{roomId}
</select>

<!-- roomParticipantMapper.xml -->
<select id="countMembersByStatus" resultType="int">
    SELECT COUNT(*) FROM room_participants
    WHERE room_id = #{roomId} AND role = 'MEMBER' AND settlement_status = #{status}
</select>
```

---

## 7. 예외 처리 (이번 기능 전용)

장보기 시작·정산 시작이 거부되면 클라이언트가 "무엇을 고쳐야 하는지" 보여줘야 하므로 상세 목록을 응답에 싣는다. **핸들러는 `SettlementController`에만 적용**하고 기존 API는 건드리지 않는다(전체 적용은 2차, §12).

```java
@Getter
@RequiredArgsConstructor
public enum SettlementErrorCode {
    UNALLOCATED_ITEMS("구매 수량과 할당 수량이 맞지 않는 품목이 있습니다."),
    MEMBER_WITHOUT_ITEMS("아무 품목도 담지 않은 참여자가 있습니다."),
    PRICE_NOT_SET("실제 가격이 입력되지 않은 품목이 있습니다.");
    private final String message;
}

// domain/settlement/exception/SettlementExceptionHandler.java
@Slf4j
@RestControllerAdvice(assignableTypes = SettlementController.class) // 정산 컨트롤러에만 적용
public class SettlementExceptionHandler {
    @ExceptionHandler(SettlementValidationException.class)   // 400 + { code, message, details }
    @ExceptionHandler(IllegalArgumentException.class)        // 400 + { code, message }
    @ExceptionHandler(IllegalStateException.class)           // 409 + { code, message }
}
```

- 기존 컨트롤러(`RoomController`)의 응답은 바뀌지 않는다. 이번에 기존 API에 추가하는 검사(품목 수정의 상태 검사, `allocateItem`의 `RECRUITING`·잠금 검사)도 기존과 같이 `IllegalStateException`을 던지고 기존 방식대로 응답한다.

---

## 8. 기존 코드 변경 목록

| 파일 | 변경 |
|---|---|
| `RoomVO` | `status` 필드 |
| `roomMapper.xml` / `RoomMapper` | `findRoomById`에 `status` SELECT, `updateStatus` |
| `roomItemMapper.xml` / `RoomItemMapper` | `findRoomItemsByRoomId`, 기존 `updateItemDetails`에 `actual_price` |
| `RoomItemServiceImpl.updateItemDetails` / `RoomDto.UpdateItemDetailsRequest` | `actualPrice` 필드, 방 상태 검사(`RECRUITING`/`SHOPPING`) |
| `participantItemMapper.xml` / `ParticipantItemMapper` | `findByRoomId` |
| `roomParticipantMapper.xml` / `RoomParticipantMapper` | `findByRoomId`, `updateSettlementStatus`, `countMembersByStatus` |
| `RoomParticipantServiceImpl.allocateItem` | `RECRUITING` 검사 + `ROOM_LOCKED(-4)` 처리 |
| `scripts/alloc.lua` | `KEYS[5]` 잠금 키 검사 |
| `SyncScheduler` | `syncItem` → `AllocationFlusher`로 추출 |
| `RoomServiceImpl.joinRoom` 관련 | `RECRUITING`에서만 참여 |
| 신규 `SettlementExceptionHandler` | §7, `SettlementController` 전용(기존 API 영향 없음) |
| `schema.sql` | **변경 없음** |

---

## 9. 테스트 계획

| 레벨 | 대상 | 케이스 |
|---|---|---|
| 단위 | `SettlementCalculator` | 균등 분할, 나머지 방장 부담(방장 할당 있음/없음), **Σ 분담금 == Σ 실제가**, 할당 부족·초과 검출, 큰 금액(오버플로) |
| 단위 | 품목 수정 API 보강 | `SETTLING`/`COMPLETED`에서 거부, `SHOPPING`에서 허용, `actualPrice` 저장 |
| 통합 | 장보기 시작 (MySQL + Redis) | Redis에만 있는 할당이 DB에 반영되는지, 시작 후 `allocateItem` 거부, **검증 실패(미할당, 아무것도 안 담은 MEMBER) 시 `RECRUITING` 복귀 + 잠금 해제 + 다시 할당 가능**, 일부 품목만 안 담은 MEMBER는 통과 |
| 동시성 | 장보기 시작 ↔ 할당 경합 | 할당 폭주 중 장보기 시작 → DB 할당 합계 == 잠금 직후 Redis 합계 |
| 동시성 | 장보기 시작·정산 시작 중복 | 동시 2회 → 1회만 성공 |
| 통합 | 할당 다시 열기 | `SHOPPING → RECRUITING`, 잠금 해제 후 할당 가능 |
| 통합 | 정산 시작 | 실제가 미입력·수량 불일치 시 `SHOPPING` 유지 + 계좌 미저장, 성공 시 계좌 키 TTL 7일 |
| 통합 | 입금 확인 | 방장만 가능, `SETTLING`에서만, HOST 대상 거부, 체크 해제 가능, MEMBER 전원 `PAID` → `COMPLETED` + 계좌·잠금 키 삭제, **`COMPLETED` 이후 변경 거부** |
| 통합 | 정산 취소 | `PAID` 있으면 거부, 없으면 `SHOPPING` 복귀 + 계좌 키 삭제 + 실제가 수정 가능 |
| 컨트롤러 | `@WebMvcTest` | 정산 API의 400(+상세)/403/409 매핑, 비참여자에게 계좌 미노출, **기존 API 응답은 변하지 않음** |

---

## 10. 결정 사항 (확정)

| # | 항목 | 결정 |
|---|---|---|
| 1 | 장보는 동안 할당 변경 | 불가. 장보기 시작 시 잠금 |
| 2 | 계좌 보관 | Redis TTL 7일 |
| 3 | 원 단위 나머지 | 방장 부담 |
| 4 | 아무 품목도 안 담은 MEMBER | 허용 안 함(장보기 시작 차단). 일부 품목만 안 담는 것은 허용, 전체 중 최소 1개 품목은 담아야 함 |
| 5 | `COMPLETED` 되돌리기 | 불가 |
| 6 | 예외 핸들러 | **이번 정산 기능에만** 적용. 기존 API는 건드리지 않음(전체 적용은 2차, §12) |
| 7 | 범위 | MVP만 구현. 나머지는 §12 "2차 구현"으로 미룸 |

남은 결정 사항 없음.

---

## 11. 작업 순서 & 예상 PR 단위

| # | PR | 내용 | 의존 |
|---|---|---|---|
| 1 | `refactor: AllocationFlusher 추출` | `SyncScheduler.syncItem` 분리, 동작 동일 | - |
| 2 | `feat: 방 상태 검사 및 장보기 시작` | `RoomVO.status`, `updateStatus`, 품목 수정 API에 `actualPrice`·상태 검사, 할당/참여 상태 검사, `alloc.lua` 잠금, 장보기 시작/할당 다시 열기, 정산 전용 예외 핸들러 + 테스트 | 1 |
| 3 | `feat: 정산 시작 및 조회` | Calculator, 정산 시작/조회(계좌 Redis 저장) + 테스트 | 2 |
| 4 | `feat: 입금 확인 및 정산 취소` | 상태 체크 API, `COMPLETED` 전이, 정산 취소 + 테스트 | 3 |

각 PR 완료 조건: `./gradlew test` 통과, 신규/변경 API의 테스트 동반.

---
계
## 12. 2차 구현 (MVP 이후)

MVP는 "장보기 시작 → 실제가 입력 → 정산 → 입금 확인 → 완료" 한 바퀴만 동작하게 한다. 아래는 이번에 만들지 않고 2차로 미룬다.

| # | 항목 | MVP에서의 대체 / 한계 | 2차에서 할 일 |
|---|---|---|---|
| 1 | 계좌 재입력 API (`PUT .../settlement/account`) | TTL 7일 만료나 Redis 장애로 계좌가 사라지면 조회 시 계좌가 비어 있음 → 방장이 MEMBER에게 직접 알려줌 | 재입력 API 추가, 또는 계좌를 DB에 저장 |
| 2 | 전역 예외 처리 (기존 API 포함) | 정산 기능 컨트롤러에만 핸들러 적용. 기존 API는 지금 응답 그대로 | `@RestControllerAdvice` 범위를 전체로 넓히고 기존 API 응답 코드 정리 + 프론트 공유 + 기존 테스트 수정. Redis 장애용 `IllegalStateException`은 별도 예외로 분리 |
| 3 | 방장이 할당을 직접 조정하는 API | 덜 산 경우 "할당 다시 열기"로 전체 할당을 다시 열어야 함 | 방장이 특정 MEMBER 할당을 줄이는 API |
| 4 | 방 나가기 / 강퇴 API | 없음. 아무 품목도 안 담은 MEMBER는 "할당하기"로만 해소 가능 | 나가기는 `RECRUITING`/`COMPLETED`에서만 허용, `SHOPPING`/`SETTLING`에서는 거부 |
| 5 | MEMBER "송금했어요" 알림 | 방장이 은행 앱에서 직접 확인 | 송금 완료 표시 + 방장 알림 |
| 6 | 미입금자 처리 (독촉·강퇴) | 고려하지 않음 | 독촉 알림, 강퇴 후 재정산 |
| 7 | 정산 미리보기 | 정산 시작 후에야 금액 확인 가능 | `SHOPPING`에서 예상 분담금 조회 |
| 8 | 정산 취소 경합 보완 | `PAID` 확인과 상태 변경 사이 경합 가능(방장 본인 요청끼리라 드묾) | `updateStatus` 조건에 `NOT EXISTS (PAID MEMBER)` 추가 |