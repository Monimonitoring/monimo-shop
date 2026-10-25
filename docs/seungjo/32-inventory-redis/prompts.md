# 2026-10-06 재고 서비스 + Redis 캐시

> 용어만 glossary(`monimo-backend/docs/design/glossary.md`) 기준으로 바꿈 (`#240`) : 원문 인용도 용어만 바꾸고 문장은 그대로 뒀다

- 관련 이슈 : `#32`
- 쓴 도구 : 웹 리서치 서브에이전트 1개, 로컬 ClickHouse 조회, Boot actuator jar 직접 열기
- 결과물 : `inventory/` 재고 API(MySQL + Redis 캐시) · `order/` 재고 호출 · 409 · 복원 · `gateway/` 재고 조회 전달 · compose(redis · inventory) · k6 · CI · 문서. 설계 한 장은 [`README.md`](README.md), 표 · Redis 키는 [`tables.md`](tables.md)

## 프롬프트

시작 (승조, 원문) :

```
시작하자
```

(앞 대화에서 "쇼핑몰 다음 작업은 재고 서비스 + Redis 입니다. 이번처럼 이슈 → 조사(research ①②) → 질문 → 결정 순서로 시작하면 될까요?" 에 대한 답)

조사 단계 (메인 대화 → 웹 리서치 서브에이전트, 원문) :

```
조사 질문 (출처 링크 필수. 순서: 공식 문서 → 오픈소스 코드 → 기술 블로그. 링크 못 찾은 주장은 "미확인"으로 표시). 한국어로, 질문 번호별로 짧게.

배경: APM(Pinpoint 비슷한 모니터링) 프로젝트의 "감시 대상" 더미 쇼핑몰이다. Kotlin 2.2 · Spring Boot 3.5 · Java 17. 각 서비스에 OpenTelemetry Java Agent 2.31.1 을 -javaagent 로 붙인다(앱 코드에 계측 없음). 서비스: gateway → order → payment → (외부 결제사 스텁). order 는 MySQL 8.4 를 JdbcTemplate 으로 쓴다. 이번에 inventory(재고) 서비스를 추가해 order 가 결제 전에 재고를 확인·차감하게 하고, inventory 가 MySQL + Redis 를 쓰게 한다. 목적은 진짜 쇼핑몰이 아니라 Service map 에 재고 노드와 캐시(Redis) 노드가 보이고, 장애 주입 시 모니터링 화면에 의미 있는 신호가 나오는 것. 백엔드 Service map 집계는 CLIENT 스팬에서 peer.service 가 있으면 SERVICE, attributes['db.system'] 이 있으면 DB, 아니면 EXTERNAL 로 분류한다.

알고 싶은 것:
1. OTel Java Agent 2.x 가 Spring Data Redis(Lettuce 기본) 호출을 어떻게 계측하나: 스팬 kind, span name, 속성(db.system=redis 인지, 새 시맨틱 컨벤션 db.system.name 으로 바뀌었는지 — 2.x 의 기본 상태와 otel.semconv-stability.opt-in 영향), server.address/port, 명령 인자(db.statement/db.query.text) 기록 여부와 마스킹. Lettuce 버전 지원 범위(Boot 3.5 의 Lettuce 6.x).
2. 부모 스팬이 없을 때(예: 스케줄러, 헬스체크 아닌 배경 호출) Lettuce 계측이 루트 CLIENT 스팬을 만드나?
3. Spring Boot Actuator 는 spring-data-redis 가 클래스패스에 있으면 RedisHealthIndicator(또는 Reactive)를 자동 등록해 /actuator/health 때마다 PING 을 보내나? 그때 OTel 이 그 PING 을 CLIENT 스팬으로 남기나(헬스체크 SERVER 스팬의 자식으로)? 끄는 설정(management.health.redis.enabled=false) 확인. 비교로 MySQL DataSourceHealthIndicator 는 Connection.isValid() 를 써서 스팬이 안 생기는지 (validation-query 미지정 시).
4. 재고 차감 패턴 비교: (a) MySQL 조건부 UPDATE `UPDATE stock SET qty = qty - ? WHERE product_id = ? AND qty >= ?` (영향 행 0이면 부족) (b) SELECT ... FOR UPDATE 비관적 락 (c) 버전 컬럼 낙관적 락 (d) Redis DECRBY/Lua 로 원자 차감 후 DB 비동기 반영. 각각 정합성·동시성·구현 크기·실패 시나리오. 업계 블로그(쿠팡/우아한형제들/컬리 등 한국 기술 블로그 포함) 사례 있으면.
5. Redis 캐시 패턴: cache-aside(look-aside), write-through, write-behind 정의와 재고 같은 자주 바뀌는 값에 쓸 때 문제(정합성, 캐시 무효화 시점). 재고 "조회"만 캐시하고 "차감"은 DB 에서 하는 흔한 구성의 장단점. Spring 의 @Cacheable/@CacheEvict 와 RedisTemplate 직접 사용의 차이(계측 관점에서 스팬이 어떻게 보이는지 포함).
6. Redis 가 죽으면: Lettuce 기본 타임아웃(command timeout 기본 60초?), Spring Boot spring.data.redis.timeout / connect-timeout 기본값, 캐시 장애를 무시하고 DB 로 가는 방법(CacheErrorHandler 등). 모니터링 데모 관점에서 "캐시 장애 → 지연 증가" 신호를 만들 수 있는지.
7. 주문 → 재고 차감 → 결제 실패 시 재고 복원(보상 트랜잭션, 사가) 의 가장 단순한 형태와, 데모 프로젝트에서 생략할 때 생기는 문제.
8. Pinpoint 데모/샘플 앱(pinpoint-apm 의 quickstart, 또는 다른 APM 의 데모 쇼핑몰: OpenTelemetry Demo 의 cart 서비스 + Valkey/Redis, Spring PetClinic 등)은 재고/캐시를 어떻게 구성했나. 특히 OpenTelemetry Demo 의 cart → valkey 구성과 장애 주입 플래그(feature flag) 종류.
9. Spring Boot 3.5 용 Redis 컨테이너 이미지 선택: redis:7.x vs valkey, 라이선스 변경(2024 Redis RSAL/SSPL, 2025 Redis 8 AGPL) 요약과 OTel 계측에서 차이 있는지.

결론 요약과 확신도(높음/중간/낮음)를 끝에.
```

구현 단계 (research 8 절, 승조 결정 원문. 초안의 두 군데와 초기값 한 줄을 AI 가 제안 → 승조가 받아들임) :

```
일단 재고 서비스는 더미 쇼핑몰 기준으로 만들고 대규모 방식은 나중에 시간이 남으면 진행하자
재고 차감은 조건부 UPDATE로 한 줄에 확인+차감하고 바뀐 행이 0이면 재고가 떨어진 것으로 하자
FOR UPDATE는 커넥션 풀 고갈 문제가 생길 수 있고(Hot Row 문제도 생길 수 있고) 낙관적 락은 너무 많이 동시성 문제가 생기면 재시도 횟수가 많아지니까 문제가되고 Redis 차감은 Redis가 죽으면 주문이 멈추니까 일단 나중에 생각하는 거로 하자

캐시는재고 조회 API만 cache-aside 차감은 DB에서 커밋한 뒤에 캐시를 지우는 방향으로 
주문만 넣으면 Service map 에 Redis가 안 보이니 k6에 주문 전에 재고 조회하는 손님을 섞는 거로 진행하자

결제 실패 시 재고 서비스에서 되돌리고 같은 주문으로 두 번 복원되지 않게 주문 번호로 해당 문제를 막자
결제 후 차감은 동시 주문이면 초과 판매가 나서 환불이 필요하니까 패스(나중에 진행해보는 거로)

actuator의 Redis 헬스체크는 끄자 켜면 고아스팬이 INFO때문에 생기고 Redis는 캐시라 죽어도 재고 서비스까지 DOWN이라고 하기엔 좀 이상하니까 

Redis가 죽으면 lettuce 기본 설정으로 인해 요청마다 60초를 기다리니까 timeout을 500ms로 줄이고 캐시가 실패하면 DB로 대신 조회하는 거로하자 이렇게 해야 주문이 계속 되면 서 화면에는 Redis 에러와 지연이 남기에 

장애 주입은 X-Shop-Fault에 inventory-slow, inventory-error를 넣고 재고 0인 품절 상품 P-SOLDOUT으로 409도 만들자
Redis 정지는 README에 시험 방법으로만 적자

order에는 peer 매핑 inventory=shop-inventory를 넣고 Redis는 매핑에 넣지 말자
redis 이미지는 redis:7.2로 하고 이름표는 shop-inventory-local-1로 하자
재고 초기값은 앱이 뜰 때 P-100은 1,000,000개 P-SOLDOUT은 0개로 넣고 이미 있으면 건드리지 말자(INSERT IGNORE, 처음부터 다시 하려면 down -v)

나중에 핫 상품에 몰려서 재고 서비스 P95가 DB 락 대기로 크게 늘면 Redis 원자 차감으로,
결제 단계가 길어져서 재고를 잡아 둘 시간이 필요하면 예약으로,
복원 호출이 실패해서 재고가 어긋나면 메시지 재시도로 바꾸자
```

이어서 승조 : "프롬프트 이걸로 가고 이 프롬프트와 지금까지의 내용을 종합해서 구현을 진행해줘 추가적으로 구현 완료후에 만들어진 파일들이 어떤 파일인지에 대한 설명과 Redis 의 경우 중간 와일드 카드 사용자 ID별 타입 이런거 설계한 것이 있다면 이거에 대한 설명도 추가해줘 /oh-my-claudecode:ralph"

## 결과 (조사 단계)

- 한 번에 됐나 : 예 (되물음 0회)
- 조사 질문이 틀렸던 것 : 3번에서 actuator 가 Redis 에 "PING" 을 보내냐고 물었는데 실제는 INFO 였다. 조사 답이 소스를 근거로 바로잡았고, Boot 3.5.16 jar 를 열어 `RedisServerCommands.info()` 호출을 다시 확인했다
- 아직 확인 안 한 것 : INFO 스팬이 헬스체크 SERVER 스팬의 자식으로 붙는지, Lettuce 6.6 의 기본 타임아웃이 main 과 같은지 (5 절에서 로컬 재현)

## 결과 (구현 단계)

- 한 번에 됐나 : 아니오
- 고쳐 물은 횟수 : 2 (사람이 아니라 E2E 시험 1회 · 별도 리뷰 1회가 잡았다)
- 무엇이 잘못 나왔나 : Redis 를 멈추고 주문하면 차감이 500 → 주문 502 가 됐다. 캐시를 "커밋 뒤에 지우기" 를 `RedisCacheManager.transactionAware()` 로 했는데, 커밋 뒤 `DEL` 이 실패하자 그 예외가 커밋 호출자까지 올라왔다. `LoggingCacheErrorHandler` 는 `@Cacheable` 같은 애노테이션 경로에만 걸리고 직접 부른 `evict` 에는 안 걸린다
- 어떻게 고쳤나 : `transactionAware()` 를 빼고 `StockService.evict` 가 커밋 뒤 동기화(`TransactionSynchronization.afterCommit`)를 직접 등록해 실패를 로그로 끝낸다. 다시 시험해 Redis 가 죽어도 주문 201 (0.3초)
- 리뷰(code-reviewer, REJECT)가 잡은 것 : `GenericJackson2JsonRedisSerializer()` 가 Kotlin data class 를 되읽지 못해 **모든 조회가 미스**였다(오류 처리기가 미스로 돌려 겉으로는 200). `Jackson2JsonRedisSerializer(jacksonObjectMapper(), StockResponse)` 로 바꾸고 되읽기 테스트를 넣었다. 같이 : 게이트웨이 `/api/stock/**` 가 POST 까지 열려 있던 것 → GET 만, 수량 0 이하 → 400, 재고 타임아웃 때도 복원 호출, 문서의 `transactionAware` 잔재
- 조사 때 몰랐던 것 : k6 처럼 같은 상품을 계속 주문하면 주문마다 `DEL` 이 나가서 조회가 거의 다 미스(`GET` → `SELECT` → `SET`)다. 히트는 주문이 뜸할 때만 난다

## 다섯 칸은 어디에 있었나

| 칸 | 프롬프트에 | 다른 곳에 |
|---|---|---|
| 목표 | 있음 (더미 기준, Service map 에 재고 · 캐시 노드) | 이슈 `#32` |
| 배경 | 있음 (각 선택지를 버린 이유) | research 2 · 5 절 |
| 범위 | 있음 (대규모 방식은 나중) | research 1 절 표 |
| 제약 | 있음 (peer 매핑에 Redis 금지, 헬스체크 끄기, timeout 500ms) | AGENTS.md §2 |
| 완료 기준 | 없음 → 이슈의 검증 칸과 PRD 로 보충 | 이슈 `#32` 검증, README 「어떻게 확인했나」 |

## 다음에 바꿀 점

완료 기준이 프롬프트에 없어서 "Redis 가 죽었을 때 주문은 되어야 한다" 가 글로는 있었지만(e1) 시험 항목으로는 늦게 잡혔다. 다음부터 결정 프롬프트 끝에 "확인할 것" 을 한 줄씩 적는다.
