# `domain/user` 심층 분석 보고서

## 1. 분석 범위와 핵심 결론

이 보고서는 `src/main/java/com/shareCart/project/domain/user`뿐 아니라 회원가입의 실제 동작을 결정하는 다음 영역을 함께 분석했다.

- `domain/town`: 회원가입 중 동네 조회·생성
- `domain/auth`: 이메일/비밀번호/role을 사용하는 로그인과 재발급
- `domain/room`: 사용자 ID와 town ID를 소비하는 방 기능
- `global/config/SecurityConfig`: 공개 회원가입 경로와 BCrypt bean
- `src/main/resources/mappers/{user,town}`: 실제 MySQL 쿼리
- MyBatis 설정, 테스트, 저장소의 DDL/마이그레이션 존재 여부

현재 user 도메인이 제공하는 기능은 **회원가입 하나**다. 회원가입 요청에 사용자의 기본 정보와 동네 전체 정보를 함께 받고, 하나의 MySQL 트랜잭션 안에서 다음을 처리한다.

1. 이메일 중복 사전 확인
2. `regionId` 기준 동네 조회 또는 생성
3. BCrypt 비밀번호 해시
4. 사용자 INSERT
5. 생성된 사용자 ID와 이름 반환

구조는 작고 명확하며, 동네 동시 생성 경합을 `DuplicateKeyException`으로 회복하려는 로직도 있다. 그러나 입력 검증이 전혀 없고, 이메일 중복은 애플리케이션의 선행 SELECT만으로 판단하며, 코드가 전제로 하는 DB UNIQUE/FK/DEFAULT 제약을 확인할 DDL이 저장소에 없다. role과 생성 시각도 INSERT하지 않아 DB 기본값에 전적으로 의존한다.

## 2. 패키지 구조와 책임

```text
domain/user
├─ controller
│  └─ UserController       POST /api/users/signup
├─ service
│  └─ UserService          회원가입 orchestration, 동네 get-or-create
└─ model
   ├─ dto/SignupDto        가입 요청/응답
   ├─ vo/UserVO            users 테이블 대응 객체
   └─ mapper/UserMapper    MyBatis 사용자 INSERT/이메일 조회
```

직접 의존 관계는 다음과 같다.

```text
UserController
  └─ UserService (@Transactional)
       ├─ UserMapper ────── users
       ├─ TownMapper ────── towns
       └─ PasswordEncoder ─ BCryptPasswordEncoder

생성된 users 데이터
  ├─ AuthService: 로그인, access token role, refresh 재발급
  └─ Room services: host/participant 식별, 사용자 town 기반 목록
```

`UserService`에 Lombok `@Getter`가 붙어 있어 세 의존성의 getter도 생성된다. 서비스 bean의 의존성을 외부에 노출할 필요는 없어 보이며 동작에는 사용되지 않는다.

## 3. 외부 API

### POST `/api/users/signup`

SecurityConfig에서 `permitAll`로 공개되어 있어 인증 없이 접근할 수 있다. 세션은 생성하지 않으며 JWT 필터는 이 요청에도 먼저 실행된다.

요청 예시 구조:

```json
{
  "name": "사용자 이름",
  "email": "user@example.com",
  "password": "raw password",
  "phone": "010-0000-0000",
  "town": {
    "regionId": "행정구역 식별자",
    "townName": "동네명",
    "sido": "시/도",
    "sigungu": "시/군/구",
    "emd": "읍/면/동",
    "latitude": 37.0,
    "longitude": 127.0
  }
}
```

성공 응답은 다음 두 필드만 포함한다.

```json
{
  "userId": 1,
  "name": "사용자 이름"
}
```

Controller는 `ResponseEntity.ok`를 사용하므로 신규 리소스 생성임에도 HTTP 상태는 `201 Created`가 아니라 `200 OK`다. Location header도 없다.

Controller와 DTO에 `@Valid`, `@NotBlank`, `@Email`, `@Size` 등이 없다. 프로젝트 의존성에도 명시적인 validation starter가 없다. 따라서 모든 필드는 null, 공백, 과도한 길이, 잘못된 형식을 포함한 채 서비스와 DB까지 전달될 수 있다.

공개 경로라도 잘못된 Bearer header가 있으면 공통 JWT 필터가 controller보다 먼저 401을 반환한다. header가 없으면 정상적으로 공개 접근된다.

## 4. 요청·응답·영속 모델

### 4.1 `SignupDto.Request`

| 필드 | 타입 | 사용처 |
|---|---|---|
| `name` | String | `users.name` |
| `email` | String | 중복 조회, `users.email` |
| `password` | String | BCrypt encode 후 `users.password` |
| `phone` | String | `users.phone` |
| `town` | TownDto | 동네 조회 또는 생성 |

Jackson이 protected no-args constructor와 필드 기반/리플렉션 접근으로 역직렬화한다. setter는 없다.

### 4.2 `TownDto`

클라이언트가 `regionId`, 행정구역 이름들, 위도·경도를 모두 보낸다. `latitude`와 `longitude`는 `BigDecimal`이라 JSON 숫자의 십진 정밀도를 보존할 수 있지만 범위와 scale 검증은 없다.

서비스는 `regionId`를 동네의 자연키처럼 사용한다. 이미 같은 regionId가 있으면 요청의 나머지 명칭/좌표는 전부 무시하고 기존 town ID를 사용한다.

### 4.3 `SignupDto.Response`

생성된 `userId`와 이름만 반환한다. 비밀번호, 전화번호, town ID, role은 노출하지 않는다.

### 4.4 `UserVO`

`users` 행을 표현하려는 mutable VO다.

- `id`
- `townId`
- `email`
- `name`
- `password`
- `role`
- `phone`
- `createdAt`
- `updatedAt`

회원가입 INSERT는 `town_id`, `email`, `name`, `password`, `phone`만 쓴다. `role`, `created_at`, `updated_at`은 DB default/trigger에 의존한다. `findByEmail` SELECT도 timestamp 두 컬럼을 읽지 않으므로 조회된 VO의 `createdAt`과 `updatedAt`은 항상 null이다. 즉 현재 VO는 테이블 전체 객체처럼 보이지만 mapper별로 부분적으로만 채워진다.

## 5. 회원가입 상세 실행 흐름

`UserController.signup` → `UserService.signup`

### 5.1 트랜잭션 시작

`UserService` 클래스는 `@Transactional(readOnly = true)`이고 `signup` 메서드는 별도 `@Transactional`로 쓰기 트랜잭션을 연다. Spring proxy를 통한 controller 호출이므로 메서드 annotation이 적용된다.

동네 INSERT와 사용자 INSERT는 같은 DB 트랜잭션에 있다. 마지막 사용자 INSERT가 실패하고 예외가 밖으로 전파되면 이 요청이 새로 만든 town도 함께 rollback되는 것이 정상 동작이다.

### 5.2 이메일 중복 사전 확인

```sql
SELECT id, town_id, email, name, phone, role, password
FROM users
WHERE email = :email
```

결과가 있으면 `IllegalStateException`을 던진다. 이 검사는 빠른 사용자 오류를 주기 위한 것이지만 동시성 제어 수단은 아니다.

두 요청이 같은 이메일로 동시에 들어오면 둘 다 SELECT에서 “없음”을 보고 INSERT까지 진행할 수 있다. 실제 중복 방지는 DB의 `users.email` UNIQUE 제약이 있어야만 보장된다. 사용자 INSERT 쪽은 `DuplicateKeyException`을 도메인 오류로 변환하지 않으므로 경쟁에서 진 요청은 raw 데이터 접근 예외로 끝난다.

이메일을 lowercase/trim/Unicode normalize하지 않는다. 중복 의미는 DB collation에 달려 있다. 예를 들어 대소문자를 같은 것으로 볼지, 앞뒤 공백을 허용할지는 코드 계약에 없다.

### 5.3 동네 resolve-or-create

`resolvedTownId(request.town)`이 다음 순서로 실행된다.

1. `towns.region_id`로 기존 town을 조회한다.
2. 있으면 그 ID를 즉시 반환한다.
3. 없으면 요청의 모든 town 필드로 `TownVO`를 구성한다.
4. `towns`에 INSERT하고 generated key를 받아 반환한다.
5. INSERT가 `DuplicateKeyException`이면 같은 regionId를 다시 조회해 ID를 반환한다.

동시에 같은 새 regionId로 가입하는 경우 둘 다 최초 SELECT에서 없다고 볼 수 있다. `towns.region_id`에 UNIQUE가 있다면 한 트랜잭션만 INSERT에 성공하고 다른 요청은 duplicate를 받은 뒤 승자의 행을 재조회하려는 패턴이다.

이 로직이 안전하려면 다음 전제가 필요하다.

- `towns.region_id`에 UNIQUE 제약이 있다.
- 중복 예외 후 같은 트랜잭션에서 경쟁자가 commit한 행을 재조회할 수 있다.
- 잡힌 DuplicateKeyException의 원인이 실제로 regionId 중복이다.

DDL이 없어 첫 번째를 확인할 수 없다. 세 번째도 코드에서 구분하지 않는다. 다른 unique 컬럼 충돌이나 잘못된 데이터 때문에 DuplicateKeyException이 나도 regionId 재조회를 하며, 결과가 null이면 `.getId()`에서 NPE가 발생한다. MySQL isolation과 최초 consistent-read snapshot 시점에 따라 경쟁자 commit 행의 재조회 가시성도 검증이 필요하다.

더 단순하고 명확한 구현은 DB가 지원하는 원자적 upsert/insert-ignore 후 ID를 확정적으로 조회하는 방식이다.

### 5.4 비밀번호 해시

`PasswordEncoder` bean은 기본 생성자의 `BCryptPasswordEncoder`다. Spring Security의 기본 BCrypt strength를 사용하며 salt는 encoder가 자동 생성한다. 따라서 같은 비밀번호도 서로 다른 hash가 생성된다. 평문은 DB에 저장되지 않고 로그인에서는 `matches(raw, encoded)`로 검증한다.

비밀번호 정책은 없다.

- 최소/최대 길이
- 문자 조합
- 유출 비밀번호 차단
- null/공백 금지
- BCrypt가 처리할 수 있는 입력 byte 길이 정책

비밀번호 encode가 동네 DB 조회/INSERT 뒤, 열린 DB 트랜잭션 안에서 수행된다. BCrypt는 의도적으로 CPU 비용이 큰 연산이므로 이를 트랜잭션 밖에서 먼저 계산하면 DB connection과 lock 점유 시간을 줄일 수 있다. 단, 먼저 입력 검증을 끝내야 한다.

### 5.5 사용자 INSERT와 응답

```sql
INSERT INTO users (town_id, email, name, password, phone)
VALUES (:townId, :email, :name, :passwordHash, :phone)
```

MyBatis `useGeneratedKeys=true`, `keyProperty=id`가 생성 PK를 같은 `UserVO`에 기록한다. 서비스는 이 ID와 이름으로 응답을 만든다.

role을 INSERT하지 않으므로 이후 로그인에서 JWT에 들어가는 `user.role`은 DB default가 반드시 제공해야 한다. null role이면 access token에도 null이 들어가고 JWT 필터의 authority 생성에서 예외가 발생할 수 있다. role 문자열이 `USER`가 아니라 이미 `ROLE_USER`라면 필터가 다시 prefix를 붙여 `ROLE_ROLE_USER`를 만든다.

## 6. DB 모델과 암묵적 제약

저장소에는 schema SQL이나 migration이 없다. 코드에서 추론되는 관계는 다음과 같다.

```text
towns
  id PK
  region_id (UNIQUE로 기대됨)
  town_name, sido, sigungu, emd, latitude, longitude
       │
       └── 1:N users.town_id

users
  id PK
  town_id FK로 기대됨
  email (UNIQUE로 기대됨)
  name, password, role, phone, created_at, updated_at
       │
       ├── 1:N rooms.host_id
       └── 1:N room_participants.user_id
```

코드가 실질적으로 기대하는 DB 조건:

1. `users.id`, `towns.id` auto-increment/generated key
2. `users.email` UNIQUE
3. `towns.region_id` UNIQUE
4. `users.town_id → towns.id` FK
5. `users.role`의 안전한 default, 일반적으로 `USER`
6. created/updated timestamp default
7. 컬럼별 적절한 NOT NULL과 길이

이 조건은 코드가 아니라 DDL로 버전 관리되어야 한다. 특히 이메일과 regionId UNIQUE가 없으면 현재 서비스의 중복 방지와 경합 회복이 모두 무너진다.

## 7. 다른 도메인에서의 사용자 데이터 사용

### 7.1 Auth

- 로그인은 `findByEmail`로 password hash와 role을 읽는다.
- access token subject에는 email, claim에는 role이 들어간다.
- refresh 재발급 때 사용자가 여전히 존재하는지 조회하고 최신 role로 access token을 만든다.
- 이메일은 Redis의 `RT:{email}`, `TV:{email}` 키에도 포함된다.

따라서 향후 이메일 변경 기능을 추가하면 JWT subject, Redis 키, 로그인 식별자 전부를 migration/revocation해야 한다. 현재 변경 기능은 없다.

### 7.2 Room

- 방 생성 시 사용자의 PK가 `rooms.host_id`가 된다.
- 방 참여 시 PK가 `room_participants.user_id`가 된다.
- 방 목록은 사용자 `townId`로 필터한다.
- 품목 수정 권한은 token email로 찾은 user ID와 room host ID를 비교한다.

사용자 town이 사실상 콘텐츠 가시성과 방 목록 범위를 결정한다. 현재 user 도메인에는 town 변경 기능이 없다.

## 8. 입력 신뢰 경계와 보안 관찰

### 8.1 클라이언트가 town master data를 생성

가입자는 행정구역 ID뿐 아니라 명칭과 좌표를 모두 제공하며 서버는 외부 authoritative source로 검증하지 않는다. 특정 regionId의 첫 INSERT가 이후 모든 사용자의 기준 데이터가 된다. 악의적이거나 오래된 클라이언트가 잘못된 명칭/좌표를 먼저 넣으면 같은 regionId의 후속 요청은 그 값을 수정하지 못한다.

가능한 개선:

- 서버 관리 town master에서 regionId만 선택하게 한다.
- 외부 행정구역 데이터와 검증한다.
- 최소한 좌표 범위, 필수 행정구역 필드, regionId 형식을 검증한다.
- 이미 존재하는 regionId와 요청 데이터가 충돌하면 로그/검증한다.

### 8.2 개인정보

사용자 테이블에는 이메일, 이름, 전화번호, 비밀번호 hash가 함께 있다. 현재 조회는 필요한 인증 정보 외에 phone도 항상 읽지만 auth/room 흐름에서는 쓰지 않는다. 목적별 projection으로 최소 컬럼만 조회할 수 있다.

전화번호 형식·정규화·중복 정책, 개인정보 보존/삭제 정책은 코드에 없다. 사용자 삭제 API도 없으며 room FK가 연결된 상태에서 탈퇴를 어떻게 처리할지도 정의되지 않았다.

### 8.3 계정 열거와 오류

회원가입은 기존 이메일에 별도 오류 메시지를 낸다. 가입 API 특성상 어느 정도 불가피할 수 있으나 rate limiting이 없으면 이메일 등록 여부를 대량 확인할 수 있다. 로그인도 존재하지 않는 이메일과 비밀번호 오류를 구분하므로 전체 시스템 차원에서 계정 열거가 쉽다.

### 8.4 대량/악성 입력

필드 길이 제한이 없어 매우 긴 문자열이 BCrypt CPU 비용, DB 오류, 로그/오류 응답 부담으로 이어질 수 있다. 가입 rate limit, CAPTCHA/봇 방어, 이메일/전화 인증은 구현되어 있지 않다.

## 9. 트랜잭션과 동시성 시나리오

| 시나리오 | 현재 동작 | 필요한 DB 조건/개선 |
|---|---|---|
| 정상 신규 town + 신규 user | 같은 tx에서 둘 다 INSERT | generated key, FK |
| 기존 town + 신규 user | town 조회 후 user INSERT | regionId index |
| 동시 같은 regionId | 한 INSERT의 duplicate를 잡아 재조회 시도 | regionId UNIQUE, isolation 검증 |
| 동시 같은 email | 둘 다 사전 조회 통과 가능 | email UNIQUE 필수, duplicate 매핑 |
| town 생성 후 user INSERT 실패 | 예외가 전파되면 전체 rollback | Spring tx/MySQL transactional table |
| BCrypt 중 예외 | 앞선 DB 변경 rollback | 예외 전파 |
| duplicate catch 후 재조회 null | NPE 발생 | 명시적 실패 처리/원자 upsert |

`signup` 트랜잭션은 여러 테이블 원자성을 제공한다는 점에서 적절하다. 다만 check-then-insert를 정확성 근거로 삼아서는 안 되고 DB constraint를 최종 권위로 삼아야 한다.

## 10. 예외와 HTTP 의미

저장소에 전역 `@ControllerAdvice`/`@ExceptionHandler`가 없다.

- 사전 이메일 중복: `IllegalStateException`
- 경쟁 이메일 중복: `DuplicateKeyException` 또는 하위 데이터 접근 예외
- 필수값 null/길이/FK 위반: DB/DataAccessException
- `town == null`: NPE
- town duplicate 회복 실패: NPE 가능
- BCrypt 입력 문제: encoder 예외

이들이 `409 Conflict`, `400 Bad Request`, `422`, `500` 등 일관된 API 계약으로 변환되지 않는다. 도메인 예외와 전역 응답 schema를 도입해야 한다.

## 11. 기능적으로 존재하지 않는 영역

현재 user 도메인에는 다음 기능이 없다.

- 사용자 상세 조회/내 정보 API
- 이름·전화번호·동네·이메일 수정
- 비밀번호 변경/재설정
- 이메일·전화번호 인증
- 계정 정지/잠금/탈퇴
- role 관리
- 중복 이메일 사전 확인 전용 API
- 개인정보 동의/보존 처리

이들은 단순 누락이라고 단정할 수는 없지만, 향후 추가 시 auth token 폐기와 room 관계를 함께 고려해야 한다. 특히 이메일, role, 비밀번호, 계정 상태 변경은 token-version을 증가시키는 정책과 연결되어야 한다.

## 12. 개선 우선순위

### P0/P1: 데이터 무결성

1. schema migration을 저장소에 추가하고 `users.email`, `towns.region_id` UNIQUE, FK, role/timestamp default를 명문화한다.
2. 사용자 INSERT의 duplicate를 잡아 동일한 `409 Conflict` 도메인 오류로 변환한다.
3. 동네 get-or-create를 원자적 upsert 패턴으로 바꾸고 재조회 null을 명시적으로 처리한다.
4. role을 애플리케이션에서 명시하거나 DB default와 enum/상수 계약을 확정한다.

### P1: 입력·보안

1. validation starter와 `@Valid`를 도입한다.
2. 이름/이메일/비밀번호/전화/town 필수값과 길이, 이메일 형식, 좌표 범위를 검증한다.
3. 이메일과 전화번호 정규화 및 중복 정책을 정한다.
4. town master data를 클라이언트 입력 그대로 신뢰하지 않는다.
5. 가입 rate limiting과 필요 시 이메일/전화 인증을 추가한다.
6. BCrypt 비용과 비밀번호 정책을 설정으로 명시한다.

### P2: API·유지보수성

1. 성공 상태를 `201 Created`로 정하고 일관된 오류 schema를 제공한다.
2. `UserService @Getter`, 주석 처리된 CORS, wildcard import를 정리한다.
3. 로그인용 사용자 projection 등 목적별 최소 조회를 고려한다.
4. VO가 실제로 항상 채워지는 필드와 부분 조회 필드를 구분한다.
5. 사용자 변경/탈퇴 시 auth와 room에 미치는 정책을 문서화한다.

## 13. 테스트와 검증 상태

직전 동일 소스 상태에서 `./gradlew.bat test`는 성공했다. 실행된 JUnit 테스트는 Spring context load와 JWT 생성 테스트 두 개뿐이며 user 회원가입을 검증하는 테스트는 없다.

우선 추가해야 할 테스트:

1. 기존 town을 사용하는 정상 가입
2. 새 town 생성과 generated town/user ID 반환
3. 같은 이메일 순차 중복과 HTTP 409
4. 같은 이메일 동시 가입에서 정확히 한 건만 생성
5. 같은 새 regionId 동시 가입에서 town 한 건과 user 여러 건 생성
6. town INSERT 후 user 실패 시 town까지 rollback
7. null/공백/잘못된 이메일/너무 긴 입력
8. 위도·경도 범위와 regionId 형식
9. BCrypt hash가 평문과 다르고 로그인 matches가 성공하는지 확인
10. DB default role과 JWT authority 연결
11. town duplicate 예외가 다른 unique 원인일 때의 처리
12. MySQL 실제 isolation level에서 duplicate 후 재조회 가시성

단위 테스트만으로는 generated key, unique 경쟁, isolation, rollback을 충분히 검증하기 어렵다. 실제 MySQL을 사용하는 Testcontainers 기반 통합 테스트가 적합하다.

## 14. 최종 평가

user 도메인은 현재 회원가입이라는 한 가지 책임에 집중되어 있고, 동네 생성과 사용자 생성을 하나의 트랜잭션으로 묶은 점은 좋다. 비밀번호를 BCrypt로 해시하고 응답에서 민감 정보를 제외하며, 동네 동시 생성 가능성도 최소한 인식한 구현이다.

그러나 정확성의 상당 부분이 저장소에 없는 DB schema에 암묵적으로 의존한다. 입력 검증과 예외 계약이 없고, 클라이언트가 town master data를 직접 결정하며, 이메일과 regionId의 경쟁 조건을 검증할 테스트도 없다. 운영 가능한 수준으로 높이려면 **DDL/migration과 UNIQUE 제약 명문화 → 원자적 중복 처리 → 입력 검증과 오류 계약 → 개인정보·계정 수명주기 정책 → MySQL 동시성 통합 테스트** 순서로 보강하는 것이 적절하다.
