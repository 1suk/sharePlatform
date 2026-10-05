# 프로젝트 개요
자취생 및 1인 가구를 위한 동네 기반 공동 장보기 / 장바구니 공유 플랫폼

# 기술 스택
- **Framework**: Spring Boot
- **Persistence**: MyBatis (Mapper / DTO / VO)
  - **DTO**: Request/Response 등 계층 간 데이터 전달용 객체
  - **VO**: DB 테이블과 1:1 매핑되는 Entity 역할의 도메인 객체
- **Database**: MySQL
- **In-Memory Store**: Redis (Refresh Token, 캐싱, 동시성 제어)
- **Security**: Spring Security, JWT
- **Build Tool**: Gradle
- **Container**: Docker, Docker Compose
- **VCS**: Git

# 아키텍처 및 코딩 컨벤션
- **계층 분리**: Controller → Service → Mapper → DB / Redis
  - **Service**: 비즈니스 로직 전담. 트랜잭션 필요 시 `@Transactional` 명시
  - **Mapper**: pure DB 접근 로직만 작성 (비즈니스 로직 금지)
  - **객체 활용**: Mapper 조회 결과는 VO로 수신하며, API 응답 시에는 DTO로 변환하여 반환
- **Redis 활용 규칙**:
  - Refresh Token, 일회성 인증번호, 캐시 데이터 등 휘발성/고성능 데이터 처리
  - 메모리 누수(OOM) 방지를 위해 데이터 저장 시 **만료 시간(TTL) 설정 필수**
- **로깅**: SLF4J (`@Slf4j`) 사용

# 작업 규칙
- 새로운 기능 구현 전에는 반드시 구현 계획을 먼저 제안하고 검토받는다.
- API 사양 변경 시 관련 단위/통합 테스트를 함께 수정한다.
- 명시적 승인 없이 DB DDL 및 마이그레이션을 실행하지 않는다.

# 검증 및 실행 명령
- **전체 빌드 및 테스트**: `./gradlew check` 또는 `./gradlew test`
- **로컬 애플리케이션 실행**: `./gradlew bootRun`
- **인프라 환경(MySQL, Redis) 실행**: `docker-compose up -d`