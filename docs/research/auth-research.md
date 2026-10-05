# `domain/auth` 심층 분석 보고서

## 1. 분석 범위와 핵심 결론

이 보고서는 다음 코드를 함께 추적해 인증의 전체 수명주기를 분석한 결과다.

- `domain/auth`: 로그인, 로그아웃, 재발급 API와 서비스/DTO
- `global/security`: JWT 생성·검증, 요청 필터, token-version 키
- `global/config`: Spring Security, BCrypt, Redis 연결
- `domain/user`: 사용자·비밀번호·역할의 생성과 조회
- `application*.yaml`: JWT 만료 시간과 Redis 설정
- 현재 테스트와 빌드 결과

구조의 중심은 **짧은 수명의 서명된 access token + Redis에 한 개만 보관하는 refresh token + Redis token-version을 이용한 access token 일괄 폐기**다. HTTP 세션은 만들지 않는 stateless Bearer 인증이며, JWT subject인 이메일이 애플리케이션 전반의 principal이 된다.

정상 흐름은 단순하고 이해하기 쉽지만, 현재 구현에는 실제 인증 동작을 크게 바꾸는 문제가 있다.

1. refresh token의 JWT 만료는 7일이지만 Redis TTL 단위를 잘못 지정해 약 **10분 4.8초** 후 재발급이 불가능해진다.
2. `/logout`이 `permitAll`이라 토큰 없는 요청도 controller까지 들어가며 `TV:null`, `RT:null`을 조작하고 200을 반환할 수 있다.
3. 로그아웃의 token-version 증가와 refresh token 삭제가 원자적이지 않다. 특히 삭제 실패 시 기존 refresh token으로 새 version의 access token을 다시 발급받을 수 있다.
4. access/refresh token의 타입 구분 claim이 없고 공통 검증 함수를 쓴다.
5. 입력 검증, 로그인 rate limiting, 전역 예외 계약, 인증 기능 테스트가 없다.

## 2. 구성 요소와 책임

```text
HTTP 요청
  │
  ├─ JwtAuthenticationFilter
  │    ├─ JwtTokenProvider: 서명/만료 검증
  │    └─ Redis TV:{email}: token-version 검증
  │
  └─ AuthController (/api/auth)
       └─ AuthService
            ├─ UserMapper: MySQL 사용자/비밀번호/역할 조회
            ├─ PasswordEncoder: BCrypt 비교
            ├─ JwtTokenProvider: access/refresh 생성
            └─ RefreshTokenService
                 └─ Redis RT:{email}, TV:{email}
```

### 파일별 역할

- `AuthController`: `/login`, `/logout`, `/reissue`를 노출하고 서비스 결과를 그대로 응답한다.
- `AuthService`: 사용자 인증과 토큰 생명주기를 조정하는 orchestration 계층이다.
- `RefreshTokenService`: Redis에 refresh token과 token-version을 저장·조회·증가시킨다.
- `JwtTokenProvider`: HMAC 서명 JWT를 생성하고 서명·만료를 검증한다.
- `JwtAuthenticationFilter`: 매 요청의 Authorization header를 검사하고 Spring Security principal을 만든다.
- `SecurityConfig`: stateless 정책, 공개 경로, 필터 순서, BCrypt bean을 정의한다.
- `TokenVersionKeys`: token-version Redis 키 형식을 한 곳에 모은다.

`AuthService`는 `@Transactional(readOnly = true)`지만 실제 로그인·로그아웃의 상태 변경은 MySQL이 아니라 Redis에서 일어난다. Spring DB 트랜잭션은 Redis 작업들을 하나의 원자적 트랜잭션으로 만들지 않는다.

## 3. API 계약

공통 prefix는 `/api/auth`다.

| Method | 경로 | Security 설정 | 요청 | 성공 응답 |
|---|---|---|---|---|
| POST | `/login` | `permitAll` | `email`, `password` | `userId`, `name`, `accessToken`, `refreshToken` |
| POST | `/logout` | `permitAll` | body 없음, principal 사용 | `200 OK`, body 없음 |
| POST | `/reissue` | `permitAll` | `refreshToken` | 새 `accessToken` |

DTO에는 `@NotBlank`, `@Email`, `@Size` 같은 Bean Validation이 없고 controller에도 `@Valid`가 없다. null/빈 문자열/비정상 크기는 서비스나 라이브러리 내부까지 들어간다.

저장소에 `@ControllerAdvice`, `@ExceptionHandler`, custom `AuthenticationEntryPoint`, `AccessDeniedHandler`가 없다. 따라서 다음 두 오류 형식이 서로 다르다.

- JWT 필터 오류: 직접 작성한 `401` JSON (`code`, `message`)
- 서비스의 `IllegalArgumentException`: Spring 기본 오류 처리에 위임되며 의도한 400/401이 보장되지 않음

## 4. 토큰과 Redis 상태 모델

### 4.1 Access token

payload에는 다음 값이 들어간다.

- `sub`: 사용자 이메일
- `role`: DB에서 조회한 사용자 역할
- `tokenVersion`: 발급 시 Redis `TV:{email}` 값, 키가 없으면 0
- `iat`: 발급 시각
- `exp`: 발급 시각 + `jwt.access-expiration`

현재 설정의 access 만료 값은 `1,800,000ms`, 즉 30분이다.

### 4.2 Refresh token

payload에는 다음 값만 들어간다.

- `sub`: 사용자 이메일
- `iat`
- `exp`: 발급 시각 + `jwt.refresh-expiration`

현재 설정의 refresh 만료 값은 `604,800,000ms`, 즉 7일이다. role, tokenVersion, token type, jti는 없다.

### 4.3 서명과 검증

`jwt.secret` 문자열의 UTF-8 byte로 `Keys.hmacShaKeyFor`를 호출해 HMAC `SecretKey`를 만든다. JJWT가 key 크기에 맞는 알고리즘을 선택해 서명한다. secret이 HMAC 최소 요구 길이보다 짧으면 애플리케이션 시작 시 key 생성이 실패할 수 있다.

검증은 동일 secret으로 signed claims를 parse하는 것뿐이다. JJWT가 서명과 만료를 검사하며, 실패 이유는 모두 잡혀 빈 `Optional`로 축약된다. issuer, audience, token type, 필수 claim 존재 여부는 provider가 확인하지 않는다.

### 4.4 Redis 키

| 키 | 값 | 의도된 수명 | 역할 |
|---|---|---|---|
| `RT:{email}` | refresh JWT 원문 | refresh 만료와 동일 | 서버가 인정하는 현재 refresh token |
| `TV:{email}` | 증가하는 정수 문자열 | refresh 만료와 동일 | 과거 access token 일괄 무효화 |

이 설계는 사용자당 refresh token 하나만 보관한다. 새 로그인은 `RT:{email}`을 덮어써 이전 기기의 refresh token을 즉시 사용할 수 없게 하지만, 이전에 발급된 access token은 같은 tokenVersion이므로 만료 전까지 계속 유효하다.

## 5. 기능별 상세 흐름

### 5.1 로그인

`POST /api/auth/login` → `AuthService.login`

1. 요청 이메일로 `users`를 조회한다.
2. 사용자가 없으면 예외를 던진다.
3. `BCryptPasswordEncoder.matches(raw, encoded)`로 비밀번호를 비교한다.
4. Redis `TV:{email}`을 읽는다. 키가 없으면 version 0이다.
5. 이메일·DB role·version을 넣은 30분 access token을 생성한다.
6. 이메일을 넣은 7일 refresh token을 생성한다.
7. refresh JWT 원문을 Redis `RT:{email}`에 저장한다.
8. 사용자 ID/이름과 두 토큰을 응답한다.

회원가입은 비밀번호를 BCrypt로 encode해 DB에 저장하므로 로그인 비교 방식과 맞는다. 회원가입 코드에서는 role을 지정하지 않으므로 실제 role은 DB default에 의존한다.

보안/운영 특성:

- 존재하지 않는 이메일과 틀린 비밀번호에 서로 다른 메시지를 사용해 계정 존재 여부를 추측할 수 있다.
- 실패 횟수 제한, 지연, 계정 잠금, IP/계정 rate limiting이 없다.
- 동일 계정 로그인은 refresh token을 교체하지만 access token을 폐기하지 않는다.
- `iat`가 같은 millisecond이고 입력 claim이 같다면 토큰이 동일해질 수 있다. 고유 `jti`는 없다.

### 5.2 인증된 일반 요청

`JwtAuthenticationFilter`는 `UsernamePasswordAuthenticationFilter`보다 먼저 매 요청당 한 번 실행된다.

1. OPTIONS 요청은 그대로 통과시킨다.
2. `Authorization`이 정확히 `Bearer `로 시작하면 뒤의 문자열을 token으로 얻는다.
3. token이 있으면 서명과 만료를 검증한다.
4. `sub`, `role`, `tokenVersion` claim을 꺼낸다.
5. Redis `TV:{email}`을 읽고, 키가 없으면 현재 version을 0으로 본다.
6. JWT version과 현재 version이 같아야 한다.
7. authority를 `ROLE_ + role`로 만들고 principal=email인 `UsernamePasswordAuthenticationToken`을 SecurityContext에 저장한다.
8. 다음 필터로 진행한다.

header 자체가 없으면 필터는 오류를 내지 않고 통과시키며, 최종적으로 공개 경로는 접근되고 보호 경로는 Spring Security가 거부한다. 반대로 공개 경로라도 잘못된 Bearer token을 붙이면 필터가 controller 전에 직접 401을 반환한다.

Redis는 모든 access-token 인증 요청의 online dependency다. Redis 연결 실패, 숫자 파싱 실패, claim 누락 등은 포괄적인 catch에서 모두 `401 AUTH_ERROR`가 된다. 인프라 장애도 인증 실패로 보이며 503으로 구분되지 않는다.

역할 문자열은 DB가 `USER`를 저장한다는 전제에서 `ROLE_USER`가 된다. DB가 이미 `ROLE_USER`를 저장하면 `ROLE_ROLE_USER`가 된다. 현재 URL 규칙은 `authenticated()`만 사용하므로 즉시 드러나지 않지만 향후 role 기반 인가 시 문제가 된다. 테스트 토큰 생성 코드는 실제로 `ROLE_USER`를 provider에 넘긴다.

### 5.3 Access token 재발급

`POST /api/auth/reissue` → `AuthService.reissue`

1. body의 refresh token을 공통 `validateToken`으로 서명·만료 검증한다.
2. token subject에서 이메일을 얻는다.
3. Redis `RT:{email}`의 JWT 원문과 요청 token을 문자열로 정확히 비교한다.
4. DB에서 사용자를 다시 조회한다.
5. Redis의 현재 `TV:{email}`을 읽는다.
6. DB의 현재 role과 현재 version으로 새 access token만 발급한다.

재발급 시 refresh token은 rotation되지 않는다. 같은 token은 Redis TTL 동안 무제한 반복 사용 가능하다. 한편 role은 DB에서 다시 읽으므로 역할 변경은 다음 재발급 access token부터 반영된다.

공통 provider는 access/refresh 타입을 구분하지 않지만, access token을 reissue body로 보내면 일반적으로 Redis의 refresh 원문과 일치하지 않아 거부된다. 이는 명시적인 타입 검증의 결과가 아니라 우연히 저장 문자열 비교에서 막히는 것이다.

### 5.4 로그아웃

유효한 access token을 Authorization header에 넣은 정상 흐름은 다음과 같다.

1. JWT 필터가 principal=email을 만든다.
2. `AuthService.logout(email)`이 `TV:{email}`을 1 증가시킨다.
3. 해당 TV 키에 refresh 만료 시간만큼 TTL을 설정한다.
4. `RT:{email}`을 삭제한다.

결과적으로 기존 access token의 version은 Redis 현재값과 달라져 즉시 거부되고, refresh token도 서버 저장본이 없어 재발급에 실패하는 것이 의도다.

하지만 SecurityConfig에서 `/api/auth/logout`이 `permitAll`이다. Bearer token 없이 호출하면 principal이 null이어도 controller가 실행되어 `TV:null` 증가와 `RT:null` 삭제를 시도하고 200을 반환한다. logout은 반드시 인증 경로로 바꾸고 null principal 방어도 추가해야 한다.

## 6. 가장 중요한 결함과 실패 시나리오

### 6.1 P0: Refresh token Redis TTL 단위 오류

JWT provider는 `refresh-expiration = 604800000`을 millisecond로 더해 7일짜리 JWT를 만든다. 그러나 `RefreshTokenService.save`는 같은 숫자를 `TimeUnit.MICROSECONDS`로 Redis에 저장한다.

```text
604,800,000 microseconds
= 604.8 seconds
= 10 minutes 4.8 seconds
```

따라서 로그인 약 10분 후 `RT:{email}`이 사라지고, JWT 자체는 아직 유효해도 재발급은 실패한다. access token은 30분짜리이므로 정상 설정에서도 access가 만료되기 훨씬 전 refresh 저장본이 먼저 사라진다. `TimeUnit.MILLISECONDS`로 통일해야 한다.

### 6.2 P0/P1: 로그아웃이 공개 경로

토큰 없는 logout이 성공처럼 보이지만 실제 사용자 세션은 전혀 폐기하지 않는다. 클라이언트는 로그아웃이 완료됐다고 오인할 수 있다. `permitAll` 목록에서 logout을 제거해야 한다.

### 6.3 P1: 로그아웃 두 Redis 명령이 비원자적

token-version 증가/TTL 설정/refresh 삭제는 세 개의 별도 명령이다.

- version 증가 후 refresh 삭제가 실패하면 기존 refresh token이 남는다.
- 재발급은 refresh token의 tokenVersion을 확인하지 않는다.
- 남은 refresh token으로 재발급하면 Redis의 **새 version**을 담은 access token이 발급된다.

즉 부분 실패 시 로그아웃을 우회할 수 있다. Lua 또는 Redis transaction으로 version 증가, TTL, RT 삭제를 원자화해야 한다. 더 강하게는 refresh token에 session/version을 넣고 재발급 시 함께 검증해야 한다.

### 6.4 P1: Token type 구분 부재

두 token 모두 같은 secret/검증기를 사용하며 `typ=access|refresh`, issuer, audience 검증이 없다. 필터는 access claim인 `role`과 `tokenVersion`이 있다고 가정해 refresh token을 Authorization에 넣으면 claim 추출 단계에서 포괄적 `AUTH_ERROR`가 난다. 목적별 claim과 전용 검증 메서드를 둬야 한다.

### 6.5 P1/P2: Token-version 기본값 0과 TTL

TV 키가 없으면 0으로 취급한다. 최초 로그인에는 편리하지만 키가 eviction/운영 실수/만료로 사라지면 version 0인 아직 유효한 token이 다시 인정될 수 있다. 현재 access 30분, TV TTL 7일이라는 의도대로라면 자연 만료 후 부활하지 않지만, 만료 설정이 뒤바뀌거나 키가 조기 유실되면 fail-open이다.

TV 증가와 TTL 설정도 별도 명령이다. 증가 성공 후 expire 실패는 키를 영구 잔존시켜 보안상 폐기는 유지하지만 저장소 누수와 세션 semantics 변화를 만든다.

### 6.6 P2: 단일 refresh 세션과 rotation 부재

- 사용자당 `RT:{email}` 하나이므로 새 로그인은 다른 기기의 재발급을 끊는다.
- 기존 access는 폐기하지 않아 “한 기기만 허용”도 완전하게 구현된 것은 아니다.
- 재발급 때 refresh token을 회전하지 않아 탈취 token의 재사용 탐지가 불가능하다.
- device/session 단위 logout이나 전체 logout을 구분할 수 없다.

이것이 의도된 단일 세션 정책인지 문서화되어 있지 않다. 다중 기기를 지원하려면 sessionId/jti별 저장 구조가 필요하다.

## 7. 추가 보안·품질 관찰

### 입력과 오류

- email/password/refreshToken에 null·공백·길이 검증이 없다.
- 로그인 오류 메시지는 계정 열거를 가능하게 한다. 외부 메시지는 통일하고 내부 로그만 구분하는 편이 안전하다.
- JWT validation은 만료/서명 오류를 구분하지 않아 클라이언트의 재로그인 판단과 운영 분석이 어렵다.
- 수동 JSON 응답과 Spring 기본 오류 응답이 섞여 API 오류 schema가 일관되지 않는다.

### 권한과 계정 상태

- access token role은 발급 시 snapshot이어서 DB role 변경이 access 만료 전까지 반영되지 않는다.
- 사용자 삭제·정지·비밀번호 변경 시 token-version을 올리는 연결 로직은 없다.
- 보호 API는 현재 role별 정책 없이 대부분 `authenticated()`만 요구한다.

### 토큰과 secret 운영

- refresh JWT 원문을 Redis에 저장한다. Redis 읽기 권한이 탈취되면 그대로 재사용할 수 있으므로 hash 저장도 고려할 수 있다.
- secret rotation 또는 key id(`kid`) 전략이 없다. secret 변경 시 모든 token이 한 번에 무효화된다.
- refresh token을 JSON body로 반환/전송한다. 클라이언트 저장 방식에 따라 XSS 노출 위험이 달라진다. 브라우저 대상이면 Secure/HttpOnly/SameSite cookie 전략과 CSRF 모델을 함께 결정해야 한다.
- CSRF, form login, HTTP basic은 비활성화되어 있다. 순수 Authorization-header API라면 자연스럽지만 실제 클라이언트 저장/전송 방식과 함께 검토해야 한다.
- 명시적 CORS 설정은 없다.

### 민감 설정

`application.yaml`은 `.gitignore`된 `application-secret.yaml`을 optional import한다. 실제 파일에는 DB/Redis/JWT 설정이 들어 있다. 값은 이 보고서에 기록하지 않았다. 저장소에는 이름이 다른 빈 `appplication-secret.yaml`도 staged 상태로 존재하며 런타임 import 대상은 아니다.

## 8. 설정상 실제 시간표

현재 설정과 코드가 만드는 사용자 경험은 다음과 같다.

```text
t=0                 로그인
                    access JWT 만료 예정: +30분
                    refresh JWT 만료 예정: +7일
                    Redis RT 만료 예정: +10분 4.8초  ← 단위 오류

t≈10분 5초          refresh 재발급 불가
t=30분              access 만료, 다시 로그인해야 함
t=7일               refresh JWT 자체 만료(실제로 도달 전에 저장본 소멸)
```

의도한 시간표는 RT Redis TTL도 7일로 두는 것이다. 보안 수준을 높이려면 refresh rotation과 절대/유휴 만료 정책을 별도로 설계할 수 있다.

## 9. 트랜잭션과 장애 의미

| 작업 | MySQL | Redis | 원자성/장애 결과 |
|---|---|---|---|
| 로그인 | 사용자 SELECT | TV read, RT set | RT 저장 실패 시 로그인 전체가 예외로 끝남 |
| 일반 인증 | 없음 | TV read | Redis 장애를 401로 응답 |
| 재발급 | 사용자 SELECT | RT read, TV read | 어느 read든 실패하면 재발급 실패 |
| 로그아웃 | 없음 | TV increment, expire, RT delete | 비원자적 부분 성공 가능 |

JWT 자체는 stateless지만 이 구현의 실제 인증은 Redis 상태에 의존하므로 완전한 stateless 시스템은 아니다. Redis 장애 시 기존 access token도 사용할 수 없는 fail-closed 성격은 보안상 안전할 수 있으나, 응답을 401이 아닌 인프라 장애로 구분하고 가용성 목표를 명확히 해야 한다.

## 10. 개선 우선순위

### 즉시 수정

1. refresh 저장 TTL을 `TimeUnit.MILLISECONDS`로 변경한다.
2. `/api/auth/logout`을 인증 필수로 변경하고 null principal을 거부한다.
3. 로그아웃의 TV 증가·TTL·RT 삭제를 Lua 한 번으로 원자화한다.
4. access/refresh에 token type을 넣고 목적별 검증기를 사용한다.
5. 로그인·재발급 DTO validation과 일관된 인증 예외 응답을 추가한다.

### 다음 단계

1. refresh rotation과 reuse detection을 도입한다.
2. 단일/다중 기기 세션 정책을 정하고 Redis 키를 session 단위로 설계한다.
3. 비밀번호 변경, 계정 정지·삭제, role 변경 시 token 폐기 정책을 연결한다.
4. rate limiting, 실패 metric, 보안 감사 로그를 추가한다. token 원문과 비밀번호는 로그에 남기지 않는다.
5. Redis 장애는 `503` 등 인증 자격 오류와 구분한다.
6. issuer/audience, key rotation, secret 관리 정책을 정의한다.
7. 외부 로그인 실패 메시지를 통일해 계정 열거를 줄인다.

## 11. 테스트와 검증 상태

직전 동일 소스 상태에서 `./gradlew.bat test`는 성공했다. 실행된 JUnit 테스트는 다음 두 개뿐이다.

- Spring context load
- `JwtTokenProvider`로 다수의 access token을 생성해 stdout에 출력

인증 동작을 assertion하는 테스트는 없다. 특히 현재 token 출력 테스트는 role에 `ROLE_USER`를 전달하므로 실제 필터에서 `ROLE_ROLE_USER`가 될 수 있고, 생성된 실제 JWT를 CI/log에 출력하는 것도 피해야 한다.

반드시 추가할 테스트:

1. 정확한 access/refresh claim과 만료 시간
2. 잘못된 서명, 만료, malformed JWT
3. Redis RT TTL이 설정값과 같은 단위인지 확인
4. 로그인 성공/없는 사용자/틀린 비밀번호/null 입력
5. 같은 사용자의 재로그인과 이전 RT 무효화
6. 재발급 성공, RT 불일치, Redis 만료, access token 오용
7. logout 후 기존 access와 refresh가 모두 실패하는지 확인
8. token 없는 logout이 거부되는지 확인
9. 로그아웃 각 Redis 단계 장애와 원자성
10. token-version 증가·키 유실·TTL 경계
11. Redis 장애 시 상태 코드와 오류 schema
12. role이 `USER`/`ROLE_USER`일 때 authority 규칙
13. 동시 재발급과 refresh rotation/reuse 시나리오

## 12. 최종 평가

이 인증 모듈은 Spring Security와 JWT의 최소 구조 위에 Redis refresh-token 저장과 token-version 폐기를 결합했다. access token을 즉시 무효화할 수 있고 재발급 때 DB의 최신 role을 읽는다는 장점이 있다. 계층의 책임도 Controller → AuthService → token/user infrastructure로 비교적 명확하다.

다만 현 상태에서는 TTL 단위 오류 때문에 의도한 7일 refresh 세션이 실제로는 약 10분이며, 공개 logout과 비원자적 폐기 흐름 때문에 보안 계약도 완전하지 않다. 우선 TTL, logout 인증, Redis 원자성을 바로잡은 뒤 token type/rotation, 오류 계약, rate limiting과 실제 통합 테스트를 갖추는 순서가 적절하다.
