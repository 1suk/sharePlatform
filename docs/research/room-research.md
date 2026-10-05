# `domain/room` 심층 분석 보고서

## 1. 분석 범위와 결론

이 문서는 `../../src/main/java/com/shareCart/project/domain/room`만 고립해서 읽은 결과가 아니다. 실제 동작을 결정하는 다음 코드까지 함께 추적했다.

- HTTP 진입점: `domain/room/controller/RoomController.java`
- 서비스와 모델: `domain/room/**`
- MyBatis SQL: `src/main/resources/mappers/room/*.xml`
- Redis Lua 스크립트: `src/main/resources/scripts/*.lua`
- Redis/JWT/Spring 설정: `global/config`, `global/security`
- 사용자 조회: `domain/user`의 `UserMapper`, `UserVO`
- 스케줄러 활성화: `ProjectApplication`
- 현재 테스트 전체와 Gradle 빌드 결과

이 도메인의 핵심은 **공동 장보기 방과 참여 관계는 MySQL에 저장하고, 품목별 실시간 수량 배분은 Redis에서 원자적으로 처리한 뒤 비동기 스케줄러가 MySQL로 반영하는 구조**다. 방 생성·참여·목록 조회는 동기식 MySQL 흐름이고, 품목 배분만 Redis를 쓰는 CQRS에 가까운 비대칭 구조다.

현재 구현은 다음 네 가지 기능을 외부에 제공한다.

1. 방 생성과 최초 품목명 등록
2. 방 참여(정원 제한 포함)
3. 방장이 품목의 단위·증감 단위·총수량 수정
4. 참여자의 품목 수량 증감 및 동네별 방 목록 조회

방 참여의 정원 증가는 조건부 SQL과 트랜잭션으로 비교적 안전하게 구현되어 있고, 품목 배분은 Lua로 동시성 제어·멱등성·총량 제한을 한 번에 처리한다. 반면 방 생성은 트랜잭션이 아니며, Redis 복구는 단일 애플리케이션 인스턴스 안에서만 잠금이 유효하고, 품목 설정 변경과 Redis 상태 사이에는 명시적인 정합성 처리가 없다. 입력 검증과 도메인 전용 테스트도 사실상 없다.

## 2. 패키지 구성과 책임

```text
domain/room
├─ controller
│  └─ RoomController              HTTP API, 인증 사용자 이메일 전달
├─ service
│  ├─ RoomService(Impl)           방 생성, 동네별 목록
│  ├─ RoomParticipantService(Impl) 방 참여, Redis 품목 배분/복원
│  └─ RoomItemService(Impl)       방장 전용 품목 상세 수정
├─ scheduler
│  └─ SyncScheduler               Redis 배분 상태를 MySQL에 주기 반영
└─ model
   ├─ dto                          API 요청/응답 객체
   ├─ vo                           DB 행 또는 조회 결과 객체
   └─ mapper                       MyBatis 인터페이스
```

이름상 계층 분리는 명확하다. Controller는 거의 위임만 하고, 비즈니스 규칙은 Service, SQL은 XML Mapper, 빠르게 변하는 배분 상태는 Redis/Lua, 영속화는 Scheduler가 담당한다.

## 3. 외부 API와 인증

모든 엔드포인트의 공통 prefix는 `/api/rooms`다. Spring Security는 `/api/rooms/create`를 명시적으로 인증 대상으로 지정하고 나머지도 `anyRequest().authenticated()`이므로 결과적으로 아래 API 전부 인증이 필요하다.

| Method | 경로 | 요청 | 성공 응답 | 주 역할 |
|---|---|---|---|---|
| POST | `/api/rooms/create` | `RoomDto.Create` | `201 Created`, body 없음 | 방 생성 |
| POST | `/api/rooms/{roomId}/join` | body 없음 | `200 OK` | 방 참여 |
| POST | `/api/rooms/{roomId}/items/allocate` | `AllocateRequest` | `200 OK` | 품목 배분 증감 |
| PATCH | `/api/rooms/{roomId}/items/{roomItemId}` | `UpdateItemDetailsRequest` | `200 OK` | 방장이 품목 세부 설정 |
| GET | `/api/rooms/list` | 없음 | `200 OK`, `Summary[]` | 로그인 사용자의 동네 방 목록 |

JWT 필터는 Bearer 토큰을 검증하고 subject인 이메일을 Spring Security principal로 설정한다. Controller의 `@AuthenticationPrincipal String email`은 이 값을 받으며, 각 서비스는 다시 `users.email`로 사용자를 조회해 내부 `user.id`로 변환한다. 즉 room API의 실질적인 사용자 식별자는 토큰의 이메일이지만, DB 관계에는 사용자 PK가 사용된다.

Controller 또는 DTO에는 `@Valid`, Bean Validation 제약, 수동 null/범위 검증이 없다. 또한 저장소 전체에서 `@ControllerAdvice`/`@ExceptionHandler`를 찾을 수 없어 서비스의 `IllegalArgumentException`, `IllegalStateException`이 의도한 4xx로 변환된다는 보장이 없다. 현재 기본 Spring 오류 처리에 맡겨진다.

## 4. 데이터 모델

### 4.1 MySQL 관계 모델

DDL/마이그레이션 파일은 저장소에 없다. 아래 관계와 컬럼은 VO와 SQL에서 역으로 복원한 것이며, PK·FK·UNIQUE·DEFAULT·NOT NULL 같은 실제 제약은 코드만으로 확정할 수 없다.

```text
users
  1 ─── N rooms                  (rooms.host_id)
  1 ─── N room_participants      (room_participants.user_id)

towns
  1 ─── N users                  (users.town_id)
  1 ─── N rooms                  (rooms.town_id)

rooms
  1 ─── N room_items             (room_items.room_id)
  1 ─── N room_participants      (room_participants.room_id)

room_participants
  1 ─── N participant_items      (participant_items.participant_id)

room_items
  1 ─── N participant_items      (participant_items.item_id)
```

주요 객체는 다음과 같다.

- `RoomVO`: `id`, `townId`, `hostId`, 시장명, 만남 장소/시간, 현재/최대 인원.
- `RoomItemVO`: 품목명, OCR 가격, 실제 가격, 총수량, 한 번의 증감 단위(`stepQty`), 표시 단위(`unit`).
- `RoomParticipantVO`: 방-사용자 연결, 역할(`HOST`/`MEMBER`), 정산 상태, 참여 시각.
- `ParticipantItemVO`: 참여자-품목 연결과 현재 배분 수량.
- `RoomList`: 목록 조회 전용 projection. 참여자 수를 집계해서 담는다.

`participant_items`의 upsert가 정상 작동하려면 `(participant_id, item_id)`에 UNIQUE 또는 PK 제약이 있어야 한다. 중복 참여 방지도 서비스가 `DataIntegrityViolationException`을 기대하므로 `(room_id, user_id)` UNIQUE 제약을 전제로 한 것으로 보이지만, DDL이 없어 확인할 수 없다.

### 4.2 DTO

- 방 생성 요청은 `townId`, `marketName`, `meetPlace`, `meetAt`, 품목명 목록을 받는다. `id`도 필드에 있으나 생성 로직에서는 사용하지 않는다.
- 최초 품목 입력은 `itemName`만 가진다. `unit`, `stepQty`, `totalQty`는 생성 후 별도 PATCH로 설정한다.
- 배분 요청은 `itemId`, `requestId`, `step`을 받는다. 실제 수량 변화는 `step * roomItem.stepQty`다.
- 방 목록 응답은 방 정보, 실제 참여자 수, 최대 인원, 최대 세 개의 품목명, 계산된 문자열 상태를 포함한다.
- `RoomParticipantDto`는 비어 있고 현재 사용되지 않는다.

MyBatis의 `map-underscore-to-camel-case: true` 설정 때문에 `room_id → roomId`, `alloc_quantity → allocQuantity` 등이 자동 매핑된다.

## 5. 기능별 상세 실행 흐름

### 5.1 방 생성

`POST /api/rooms/create` → `RoomServiceImpl.createRoom`

1. principal 이메일로 사용자를 조회한다. 없으면 예외다.
2. 요청의 `townId`, 시장명, 장소, 시간을 사용하고 로그인 사용자를 `hostId`로 하여 `rooms`에 INSERT한다.
3. MyBatis generated key를 `RoomVO.id`에 되돌려 받는다.
4. 품목 목록이 비어 있지 않으면 `room_items`에 품목명을 다중 INSERT한다.
5. 생성자를 `role = "HOST"`인 `room_participants` 행으로 INSERT한다.

중요한 특성:

- `maxParticipants`와 `currentParticipants`는 요청/INSERT에 없어서 DB 기본값에 의존한다.
- 생성 요청의 `townId`가 로그인 사용자의 `townId`와 같은지 검사하지 않는다. 따라서 다른 동네의 방을 생성할 수 있고, 목록은 사용자 동네 기준이므로 생성자가 자기 방을 목록에서 못 볼 수도 있다.
- 이 메서드에는 `@Transactional`이 없다. 방 INSERT 후 품목 또는 HOST 참여자 INSERT가 실패하면 앞선 데이터가 남을 수 있다.
- 품목이 하나도 없는 방도 허용된다.

### 5.2 방 참여

`POST /api/rooms/{roomId}/join` → `RoomParticipantServiceImpl.joinRoom`

1. 이메일로 사용자를 조회한다.
2. `(roomId, userId)`로 기존 참여를 먼저 확인한다.
3. 방 존재 여부를 조회한다.
4. 다음 조건부 UPDATE를 실행한다.

```sql
UPDATE rooms
SET current_participants = current_participants + 1,
    updated_at = NOW()
WHERE id = :roomId
  AND current_participants < max_participants
```

5. 갱신 행이 0이면 정원이 찼거나 방이 사라진 것으로 처리한다.
6. `role = "MEMBER"`로 참여자 행을 INSERT한다.

클래스는 기본 `@Transactional(readOnly = true)`지만 이 메서드는 `@Transactional`로 재정의되어 쓰기 트랜잭션이다. 따라서 참여자 INSERT가 중복 제약 등으로 실패하면 앞선 인원수 증가도 롤백된다. 정원 경쟁은 `SELECT COUNT` 후 판단하지 않고 DB의 원자적 조건부 UPDATE로 해결하여 동시에 여러 요청이 들어와도 `max_participants`를 넘는 증가를 막는다.

다만 아래 정책은 구현되어 있지 않다.

- 사용자와 방의 동네 일치
- 만남 시간이 지났거나 마감 임박한 방의 참여 제한
- HOST가 다시 join하는 경우에 대한 별도 의미
- 탈퇴와 `current_participants` 감소

또한 방 목록의 현재 인원은 `room_participants COUNT`로 계산하지만 참여 가능 여부는 `rooms.current_participants`를 사용한다. 방 생성 시 DB 기본값이 HOST를 포함한 `1`이 아니거나 데이터가 수동 변경되면 화면상의 인원과 정원 판정이 달라질 수 있다.

### 5.3 품목 상세 수정

`PATCH /api/rooms/{roomId}/items/{roomItemId}` → `RoomItemServiceImpl.updateItemDetails`

1. 사용자와 방의 존재를 확인한다.
2. `rooms.host_id == user.id`인지 검사한다.
3. 품목이 존재하고 그 품목의 `room_id`가 path의 방과 일치하는지 검사한다.
4. `unit`, `step_qty`, `total_qty`를 한 번에 UPDATE한다.

방장만 수정 가능하다는 권한과 path 소속 검증은 있다. 하지만 값의 null, 양수 여부, 이미 배분된 수량보다 `totalQty`를 작게 낮추는 경우를 검증하지 않는다. 더 중요하게는 이미 생성된 Redis의 `total:{itemId}`나 배분 ZSET을 무효화하거나 재계산하지 않는다. 이후 배분 요청은 DB에서 새 `totalQty`/`stepQty`를 읽지만 Redis 누적 총량은 그대로이므로, 총량 축소 시 현재 배분량이 새 cap을 초과한 상태가 그대로 존재할 수 있다.

### 5.4 동네별 방 목록

`GET /api/rooms/list` → `RoomServiceImpl.getRoomList`

1. 이메일로 사용자를 찾고 `user.townId`를 얻는다.
2. 해당 동네의 모든 방을 한 번에 조회한다. `LEFT JOIN room_participants`와 `COUNT(rp.id)`로 실제 참여자 수를 계산한다.
3. 방 ID 전체를 IN 쿼리에 넘겨 모든 품목명을 한 번에 읽는다.
4. Java에서 `roomId → itemName[]`으로 그룹화하고 방마다 앞의 세 개만 남긴다.
5. 만남 시간과 인원으로 상태 문자열을 계산한다.

이전의 방별 품목 조회 대신 배치 IN 조회를 사용하므로 N+1 문제는 피한다. 품목은 `room_items.id ASC` 순으로 조회되어 방별 최초 세 품목이 선택된다. 방 자체의 정렬과 페이징은 없다.

상태 우선순위는 다음과 같다.

1. 현재 시각이 `meetAt` 이후면 마감
2. 현재 인원이 최대 인원 이상이면 모집 완료
3. 현재 시각 + 30분이 `meetAt` 이후면 마감 임박
4. 그 외 모집 중

즉 이미 지난 방은 정원이 차 있어도 “마감”이 우선이다. 정확히 30분 전은 `isAfter`의 엄격 비교 때문에 경계 순간에는 임박으로 판정되지 않을 수 있다. `meetAt` 또는 인원 컬럼이 null이면 unboxing/시간 비교에서 예외가 난다.

### 5.5 품목 수량 배분

`POST /api/rooms/{roomId}/items/allocate` → `RoomParticipantServiceImpl.allocateItem`

사전 검증:

1. 로그인 사용자가 DB에 존재해야 한다.
2. 사용자가 해당 방의 `room_participants`여야 한다.
3. `itemId`가 존재하고 품목의 `roomId`가 요청 path와 일치해야 한다.
4. 변화량 `delta = request.step * item.stepQty`를 계산한다.

Redis 키는 다음과 같다.

| 키 | 자료구조 | 의미 | TTL |
|---|---|---|---|
| `zset:{itemId}` | Sorted Set | member=participantId, score=개인 배분량 | 성공 배분마다 7일 |
| `total:{itemId}` | String integer | 해당 품목 전체 배분량 | 성공 배분마다 7일 |
| `dedup:{itemId}:{participantId}:{requestId}` | String | 같은 요청의 재실행 방지 | 1일 |
| `dirty:items` | Set | DB 반영이 필요한 itemId | 코드상 TTL 없음 |
| `processing:items` | Sorted Set | 동기화 중 itemId, score=claim 시각(ms) | 코드상 TTL 없음 |

배분 직전 `zset:{itemId}`가 없으면 DB의 `participant_items`를 읽어 Redis 복원을 시도한다. 그 다음 `alloc.lua`가 단일 Redis 스크립트로 아래 작업을 원자적으로 수행한다.

1. dedup 키가 있으면 `-1`을 반환하고 아무 것도 바꾸지 않는다.
2. 감소 후 개인 수량이 0 미만이면 `-2`를 반환한다.
3. 증가 후 전체 수량이 DB의 현재 `totalQty`를 넘으면 `-3`을 반환한다.
4. ZSET의 개인 score와 total 키를 `delta`만큼 증가시킨다.
5. ZSET/total TTL을 7일로 갱신한다.
6. dedup 키를 1일 동안 만든다.
7. itemId를 `dirty:items`에 넣는다.

이 덕분에 같은 Redis 인스턴스에 대한 동시 요청은 개인 음수와 전체 초과를 원자적으로 막는다. 같은 `requestId`의 재시도도 24시간 동안 성공으로 간주되어 조용히 반환된다. Redis 연결 실패/timeout과 OOM 일부는 사용자용 `IllegalStateException`으로 변환하고 로그를 남긴다.

주의할 점:

- `step`이 반드시 `+1/-1`일 필요가 없고 0이나 큰 수도 허용된다.
- `step`, `stepQty`, `totalQty`, `requestId`의 null/형식 검증이 없다. null은 NPE, Lua 숫자 변환 오류, 또는 문자열 `"null"` dedup 키를 만들 수 있다.
- Java `int` 곱셈이므로 큰 값은 Redis에 가기 전에 overflow할 수 있다.
- 0까지 감소해도 ZSET member를 삭제하지 않고 score 0으로 유지한다. DB에도 0인 행이 upsert된다.
- 멱등성은 영구 보장이 아니라 24시간 창이다. 같은 requestId를 하루 뒤 재전송하면 다시 적용된다.
- 만남 종료 여부나 방 상태는 배분 시 검사하지 않는다.

## 6. Redis 캐시 복원

`restoreIfMissing`은 ZSET이 없을 때만 실행된다.

1. `hasKey(zset)`로 빠르게 확인한다.
2. `ConcurrentHashMap<itemId, lock>`에서 JVM 로컬 lock을 가져와 synchronized 진입한다.
3. double-check 후 MySQL `participant_items`를 조회한다.
4. UUID가 붙은 임시 ZSET에 참여자별 DB 수량을 적재하고 합계를 계산한다.
5. 임시 ZSET에 5분 TTL을 둔다.
6. `restoreAlloc.lua`로 임시 ZSET을 실제 키로 RENAME하고 total 키를 합계로 SET한다.

임시 키를 구성한 뒤 Lua에서 교체하므로 같은 JVM 안에서는 불완전한 ZSET이 외부에 보이는 것을 피한다. 하지만 다음 한계가 있다.

- lock은 프로세스 로컬이다. 다중 서버에서는 두 인스턴스가 동시에 복원할 수 있고 `RENAME`이 그 사이 발생한 정상 배분을 덮어쓸 수 있다.
- `restoreLocks`에서 lock 객체를 제거하지 않아 접근한 itemId 수만큼 장기적으로 Map이 증가한다.
- ZSET 존재만 검사한다. ZSET은 남고 `total`만 유실된 경우 total을 0으로 간주해 총량 초과 배분이 가능하다.
- DB 배분이 비어 있으면 아무 복원도 하지 않는다. 이때 과거의 `total` 키만 남아 있다면 잘못된 총량을 계속 사용한다.
- RENAME된 ZSET은 임시 키의 5분 TTL을 승계한다. 바로 뒤의 성공적인 `alloc.lua`가 7일로 연장하지만, 복원 후 배분이 거절되거나 실패하면 5분 후 다시 사라진다. 반면 복원 스크립트의 total 키에는 TTL을 설정하지 않는다.
- DB는 비동기 복제본 성격이므로 아직 스케줄러가 반영하지 않은 Redis 상태가 유실된 뒤에는 완전 복구할 수 없다.

## 7. Redis → MySQL 비동기 동기화

`@EnableScheduling`이 활성화되어 있으며 `SyncScheduler`에는 두 작업이 있다.

### 7.1 정상 동기화: 30초 fixed delay

1. `claimDirtyItems.lua`가 `dirty:items`에서 최대 100개를 `SPOP`한다.
2. 각 itemId를 현재 epoch millisecond score와 함께 `processing:items`에 `ZADD NX`한다.
3. claim된 각 품목의 `zset:{itemId}` 전체를 읽는다.
4. 각 `(participantId, score)`를 `ParticipantItemVO`로 바꾼다.
5. 한 품목의 모든 참여자 배분을 MySQL 다중 INSERT ... ON DUPLICATE KEY UPDATE로 저장한다.
6. 성공하면 `ackProcessingItem.lua`가 itemId와 claim timestamp가 여전히 일치할 때만 processing에서 제거한다.

스케줄러는 Redis ZSET의 **절대 현재값**을 DB에 upsert한다. delta 이벤트를 재생하는 방식이 아니어서 동일 snapshot을 여러 번 써도 결과가 같다. 동기화 도중 새 배분이 생기면 `alloc.lua`가 itemId를 dirty set에 다시 넣으므로 다음 주기에 최신 snapshot이 반영된다.

### 7.2 장애 복구: 10초 fixed delay

`processing:items`에서 30초보다 오래된 itemId를 최대 100개 읽고, `recoverProcessingItems.lua`가 score가 여전히 cutoff 이하인지 확인한 뒤 dirty set으로 되돌리고 processing에서 제거한다. 프로세스 장애나 DB 오류로 ACK되지 않은 작업이 영구 유실되지 않게 하는 장치다.

### 7.3 전달 보장과 경계 사례

이 구조는 실질적으로 at-least-once 재처리와 마지막 snapshot upsert를 조합한다. claim, recover, ack에는 Lua와 timestamp fencing이 있어 단순 중복 실행에는 강하다. 다만 엄밀한 선형 일관성이나 정확히 한 번 처리는 아니다.

- DB 반영 지연은 정상적으로 최소 수 초에서 30초 이상이다. 그동안 DB 직접 조회는 낡은 배분을 본다.
- ZSET이 없거나 비어 있으면 성공으로 ACK하지만 DB의 기존 행을 0으로 만들거나 삭제하지 않는다. 키 유실 상황에서는 오래된 DB 값이 남는다.
- ZSET score(double)를 `intValue()`로 변환하므로 범위가 크거나 정수가 아닌 데이터는 손실/절삭될 수 있다.
- 잘못된 itemId나 잘못된 ZSET member는 로그 후 건너뛰고, 유효 batch가 없으면 작업 자체는 성공 처리된다.
- 처리 시간이 30초를 넘으면 recovery가 같은 품목을 다시 dirty로 보낼 수 있다. 이전 작업과 새 작업이 겹쳐도 대개 절대값 upsert로 수렴하지만, **이전의 느린 DB 쓰기가 더 최신 snapshot 쓰기 뒤에 완료되면 DB를 과거 값으로 되돌릴 수 있다**. ACK timestamp fencing은 Redis 큐 마커만 보호하며 DB write 자체에는 버전 조건이 없다.
- 이미 processing 중인 itemId를 claim 스크립트가 dirty set에서 뽑으면 다시 dirty에 넣는다. Set의 무작위 SPOP 특성상 한 batch 루프에서 같은 항목을 반복해서 뽑아 다른 항목 처리를 지연시킬 가능성이 있다.

## 8. 트랜잭션과 정합성 경계

| 작업 | 주 저장소 | 트랜잭션/원자성 | 정합성 특성 |
|---|---|---|---|
| 방 생성 | MySQL | 서비스 트랜잭션 없음 | 부분 생성 가능 |
| 방 참여 | MySQL | Spring 트랜잭션 + 조건부 UPDATE | 정원 경쟁과 INSERT 실패 rollback 대응 |
| 품목 설정 변경 | MySQL | 단일 UPDATE | Redis 상태와 별도 |
| 품목 배분 | Redis | Lua 단일 실행 | Redis 내부에서는 원자적 |
| 배분 영속화 | Redis → MySQL | 품목별 batch upsert | 최종 일관성, 재처리 가능 |
| Redis 복원 | MySQL → Redis | 로컬 lock + Lua rename | 단일 JVM 위주, DB 최신성에 의존 |

가장 중요한 불변조건은 다음과 같다.

- 방 참여자 수는 최대 인원을 넘지 않아야 한다.
- 개인 배분량은 0 이상이어야 한다.
- 품목 전체 배분량은 `room_items.total_qty` 이하여야 한다.
- 방 참여자만 그 방의 품목을 배분할 수 있다.
- 품목 설정은 방장만 바꿀 수 있다.

구현은 정상 요청 경로에서 이 불변조건 대부분을 지키지만, 데이터가 이미 불일치하거나 Redis 키 일부가 유실되거나 total cap이 사후 변경되는 경우까지 복구하지는 않는다.

## 9. 발견된 위험과 개선 우선순위

### P0/P1: 정합성과 데이터 손실 가능성

1. **방 생성에 트랜잭션 적용 필요**  
   `rooms` → `room_items` → HOST participant를 하나의 트랜잭션으로 묶어야 부분 데이터가 남지 않는다.

2. **Redis 복원에 분산 동시성 제어 필요**  
   로컬 synchronized는 다중 인스턴스를 보호하지 않는다. Redis 자체의 restore lock 또는 “키가 없을 때만 교체”하는 fencing/version 전략이 필요하다.

3. **품목 cap 변경과 현재 배분량의 원자적 검증 필요**  
   totalQty를 낮출 때 현재 Redis/DB 배분 합계보다 작지 않은지 검사하고, Redis cap/상태 갱신 정책을 정의해야 한다.

4. **느린 동기화 작업의 stale write 방지 필요**  
   item별 version을 Redis snapshot과 DB에 함께 기록하거나, timeout보다 오래된 writer가 최신 version을 덮어쓰지 못하도록 조건부 UPDATE가 필요하다.

5. **복원 키 TTL과 부분 키 유실 처리 재설계 필요**  
   ZSET/total을 하나의 논리 상태로 검증하고 같은 TTL로 관리해야 한다. 복원 성공 후 final 키 TTL도 Lua 안에서 명시하는 편이 안전하다.

### P1/P2: 도메인 규칙과 API 안정성

1. 생성 동네와 사용자 동네 일치, 종료된 방 join/allocate 금지 등 정책을 명시하고 검증해야 한다.
2. `step ∈ {-1, +1}` 여부, requestId nonblank/길이, `stepQty > 0`, `totalQty >= 0`, 필수 문자열/시간을 검증해야 한다.
3. 곱셈은 `Math.multiplyExact` 또는 long으로 처리해 overflow를 막아야 한다.
4. 예외를 일관된 HTTP 상태/에러 body로 매핑하는 전역 예외 처리기가 필요하다.
5. `rooms.current_participants`와 실제 participant COUNT 중 하나를 authoritative source로 정하고 생성/탈퇴/복구 경로를 일치시켜야 한다.
6. 목록에 정렬과 페이징을 추가해야 데이터 증가 시 응답 크기와 IN 쿼리를 제어할 수 있다.
7. 사용하지 않는 import, 빈 DTO, 주석 처리된 과거 구현을 정리하면 실제 설계 의도가 선명해진다.

### 운영 관측성

- 배분 성공/거절/중복, dirty backlog 크기, processing timeout/recovery 수, DB sync latency와 실패 횟수에 대한 metric이 없다.
- 스케줄러는 예외를 삼키고 로그만 남기므로 장기 장애가 API에는 드러나지 않는다.
- `dirty:items`, `processing:items`, `restoreLocks`의 크기와 오래된 항목을 관찰할 수 있어야 한다.

## 10. 테스트와 검증 결과

2026-10-04 기준 `./gradlew.bat test`를 실행했고 결과는 **BUILD SUCCESSFUL**이었다. 컴파일, 리소스 처리, 테스트 컴파일과 테스트가 모두 성공했다.

실제로 실행된 JUnit 테스트는 2개다.

- `ProjectApplicationTests.contextLoads`: Spring context 로드 확인
- `JunitTest.generateCsvTokens`: 테스트 사용자 JWT 출력

`DataTest`는 `main` 메서드만 있고 JUnit 테스트가 아니다. room 도메인에 대한 단위/통합/동시성 테스트는 없다. 따라서 빌드 성공은 wiring과 기본 context 기동을 확인할 뿐, 이 보고서에서 설명한 도메인 불변조건을 검증하지 않는다.

우선 추가할 테스트:

1. 동시에 `maxParticipants + N`명이 join해도 정확히 정원만 성공하는 통합 테스트
2. 중복 join 시 인원 증가가 rollback되는 테스트
3. 방 생성 중 품목/HOST INSERT 실패 시 전체 rollback 테스트
4. Lua의 중복 requestId, 개인 음수, 총량 초과, 동시 증가 테스트
5. Redis 키 완전/부분 유실 후 DB 복원 테스트
6. 배분 도중 scheduler snapshot이 겹칠 때 최신값으로 수렴하는 테스트
7. processing timeout과 늦은 writer가 겹치는 테스트
8. totalQty 축소 및 stepQty 변경 중 기존 배분 정합성 테스트
9. 인증, 방장 권한, 타 방 itemId 사용, 타 동네/마감 방 정책 테스트
10. 목록 상태의 시간 경계(정확히 30분, 정확히 meetAt)와 품목 3개 제한 테스트

## 11. 최종 평가

`domain/room`은 단순 CRUD가 아니라 두 종류의 경쟁 조건을 명시적으로 다룬다. 방 정원은 MySQL 조건부 UPDATE로, 품목 배분은 Redis Lua로 처리한다. dirty/processing 큐와 timestamp 기반 ACK/recovery도 장애 시 재처리를 염두에 둔 설계다. 특히 방 목록의 품목 N+1을 일괄 조회로 피하고, 배분 DB 저장을 delta가 아닌 절대 snapshot upsert로 만든 점은 재시도 안정성에 유리하다.

그러나 현재 안정성은 “단일 인스턴스, 정상적인 키 생명주기, 유효한 입력, 빠른 DB 동기화”라는 전제에 많이 의존한다. 운영 수준으로 끌어올릴 때 가장 먼저 해결할 것은 방 생성 트랜잭션, 분산 환경의 Redis 복원 fencing, 품목 cap 변경 정합성, stale scheduler write 방지, 입력/예외 계약, 그리고 실제 동시성 테스트다. DDL도 저장소에 포함해 코드가 암묵적으로 기대하는 UNIQUE·DEFAULT·FK 제약을 명문화해야 한다.
