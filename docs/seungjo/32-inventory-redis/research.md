# 리서치 : 재고 서비스 + Redis 캐시

> 용어만 glossary(`monimo-backend/docs/design/glossary.md`) 기준으로 바꿈 (`#240`) : 원문 인용도 용어만 바꾸고 문장은 그대로 뒀다

- 날짜 2026-10-06 / 관련 이슈 `#32`
- 쓴 도구 : 웹 리서치 서브에이전트 1개 · 로컬 ClickHouse 조회 · Boot actuator jar 직접 열어 확인 · backend Service map 집계 SQL 읽기

> **읽는 순서가 곧 쓰는 순서다.** 1 → 2 를 쓰고 멈춰서 승조가 3 을 묻는다. 답하고 4 를 쓴다.
> 5 를 쓰고 멈춰서 승조가 6 을 묻는다. 답하고 7 을 쓴다. 8 은 승조가 쓴다.
> **3 · 6 · 8 은 사람 차례다.** 비어 있으면 아직 거기까지 온 것이고, 대신 채우지 않는다.

---

## 1. 이번 작업은 무엇을 하는 일인가

- **지금 무엇이 잘못됐나** : 쇼핑몰 네 서비스 중 재고만 빈 앱이라, Service map 에 재고 노드와 캐시 노드가 없다.
- **그래서 무엇을 만드나** : 주문이 결제 전에 재고를 확인하고 줄이게 한다. 재고 서비스는 MySQL 에 재고를 두고 Redis 를 캐시로 쓴다.
- **안 하면 어떻게 되나** : Service map 이 `gateway → order → payment` 한 줄뿐이라 "캐시가 느려져서 주문이 느려졌다" 같은 장애를 보여 줄 재료가 없다. ADR `#17` 이 PetClinic 을 버린 이유(재고 · 캐시 노드가 없다)가 그대로 남는다.

| | |
|---|---|
| 건드리는 모듈 · 파일 | `inventory/` (빈 앱 → 재고 API), `order/` (재고 호출 추가), `docker-compose.dev.yml`(redis · inventory), `k6/order.js`, CI smoke, `gradle/libs.versions.toml`(Redis 의존성) |
| 건드리지 않는 것 | 게이트웨이 · 결제, 에이전트 설정(`otel/agent.properties`), 백엔드 |
| 이 이슈 범위 밖 (후속) | Kafka(나중에), 백엔드 Service map 이 새 DB 속성 이름(`db.system.name`)도 보게 하는 일(백엔드 몫, 2.6 참고) |

**조사 들어가기 전에 내가 알던 것 (세 줄)** : 

- 
- 
- 

**알고 싶었던 것**

① Redis 호출은 Service map 에 어떻게 잡히나 ② 재고를 줄이는 안전한 방법은 ③ 캐시를 어디에 쓰나 ④ Redis 가 죽으면 쇼핑몰은 어떻게 되나 ⑤ 결제가 실패하면 줄인 재고는 ⑥ 헬스체크와 부딪치는 곳은 없나 ⑦ 남(OTel Demo · Pinpoint)은 어떻게 하나

## 2. 작업하기 전에 알아야 하는 것

### 2.1 재고 서비스가 들어가면 주문 흐름이 어떻게 바뀌나

지금 `OrderService.create` 는 주문을 `PENDING` 으로 저장 → 결제 호출 → `PAID` 나 `FAILED` 로 고친다. 여기에 결제 **앞에** 재고 호출이 하나 끼어든다.

```
지금 :  손님 → gateway → order ─┬─ MySQL (주문 저장)
                                └─ payment → pg-stub

이후 :  손님 → gateway → order ─┬─ MySQL (주문 저장)
                                ├─ inventory ─┬─ MySQL (재고 표)
                                │             └─ Redis (캐시)
                                └─ payment → pg-stub   ← 재고가 있을 때만
```

재고가 모자라면 주문은 결제를 부르지 않고 **409**(요청은 맞지만 지금 상태로는 처리할 수 없음)로 끝난다. 지금 주문이 내는 응답은 201 · 502 · 404 셋이라, 409 는 새 응답이다. gateway 는 상태 코드를 그대로 넘기므로(`#25`) 손님에게도 409 가 간다.

### 2.2 Service map 은 Redis 를 무엇으로 보나

backend 의 Service map 집계(`db/clickhouse/004_create_materialized_views.sql` 의 `mv_server_map_1m`)는 **부른 쪽의 CLIENT 스팬**만 보고 세 칸으로 나눈다.

| 스팬에 있는 것 | 칸 | 지금 쇼핑몰 예 |
|---|---|---|
| `peer_service` 가 있다 | SERVICE (우리 서비스) | order → `shop-payment` |
| `attributes['db.system']` 이 있다 | DB | order → `mysql:3306` (`db.system=mysql`) |
| 둘 다 없다 | EXTERNAL (외부) | payment → `pg-stub:8080` |

에이전트(2.31.1)가 Redis 호출을 기록하면 스팬 이름이 명령 이름(`GET` · `SET`)이고 `db.system=redis` 가 붙는다. 그래서 Redis 는 **MySQL 과 같은 DB 칸**에 `redis:6379` 로 잡힌다. Service map 에 "캐시" 라는 칸이 따로 있는 게 아니다.

두 가지를 조심해야 한다.

- **`peer-service-mapping` 에 redis 를 넣으면 안 된다.** `#23` 에서 order 에 `payment=shop-payment` 를 넣었는데, 같은 식으로 `redis=...` 를 넣으면 `peer_service` 가 먼저 보여 Redis 가 SERVICE 로 잘못 잡힌다. 넣을 짝은 order 의 `inventory=shop-inventory` 하나다
- 에이전트의 다음 큰 버전(3.0 으로 추정)은 `db.system` 대신 새 이름 `db.system.name` 을 쓴다. 지금 Service map 은 `db.system` 만 보므로, 에이전트를 올리는 날 MySQL · Redis 가 EXTERNAL 로 떨어진다. 이번 작업 범위는 아니지만 backend 에 알려 둘 일이다

### 2.3 재고를 줄이는 법 : 동시에 두 명이 사면

재고 1개 남은 상품을 두 주문이 동시에 사면 둘 다 "1개 있네" 를 읽고 둘 다 줄여 -1 이 될 수 있다. 막는 방법이 몇 가지 있다.

| 방법 | 한 줄 |
|---|---|
| 조건부 UPDATE | `UPDATE stock SET qty = qty - 2 WHERE product_id = 'P-100' AND qty >= 2`. MySQL 이 그 행을 잠그고 한 번에 확인 + 차감한다. 바뀐 행이 0 이면 부족 |
| `SELECT ... FOR UPDATE` | 먼저 읽으면서 잠그고, 확인한 뒤 줄인다. 트랜잭션이 끝날 때까지 다른 주문이 기다린다 |
| 버전 컬럼 (낙관적 락) | 잠그지 않고 "내가 읽은 버전일 때만 바꿔라". 충돌하면 다시 시도 |
| Redis 에서 차감 | Redis 의 `DECRBY`(또는 Lua 스크립트)로 줄이고 DB 에는 나중에 반영. 빠르지만 Redis 와 DB 가 어긋날 수 있다 |

자세한 비교는 5 절에서 한다.

### 2.4 캐시를 쓰는 방식 세 가지

캐시는 "자주 읽는 값을 빠른 곳(Redis)에 복사해 두는 것" 이다. 재고처럼 **자주 바뀌는 값**은 복사본이 낡는 게 문제다.

| 방식 | 읽을 때 | 쓸 때 |
|---|---|---|
| cache-aside (look-aside) | Redis 에 있으면 그걸 쓰고, 없으면 DB 에서 읽어 Redis 에 넣는다 | DB 를 바꾸고 Redis 값을 지운다(evict) |
| write-through | Redis 에서 읽는다 | DB 와 Redis 를 같이 바꾼다 |
| write-behind | Redis 에서 읽는다 | Redis 만 바꾸고 DB 는 나중에. 빠르지만 Redis 가 죽으면 잃는다 |

흔한 구성은 "조회만 캐시하고 차감은 DB 에서" 다. 이때 Redis 값을 지우는 시점이 **DB 커밋 뒤**여야 한다. 커밋 전에 지우면 그 틈에 다른 요청이 옛 값을 다시 채워 넣을 수 있다.

Spring 에서는 `@Cacheable` · `@CacheEvict` 애노테이션으로 하거나 `RedisTemplate` 으로 직접 부른다. 에이전트는 Spring 캐시 자체를 따로 기록하지 않아서, 어느 쪽이든 화면에는 그 밑의 Redis `GET` · `SET` · `DEL` 스팬만 보인다. "캐시 히트였나 미스였나" 는 스팬에 저절로 나오지 않는다.

### 2.5 Redis 가 죽으면 : 기본값은 60초를 기다린다

Spring Boot 가 쓰는 Redis 클라이언트(Lettuce)는 기본 설정이 이렇다 (Lettuce 소스 main 기준, Boot 3.5 의 Lettuce 6.6 에서 같은지는 미확인).

| 설정 | 기본값 | 뜻 |
|---|---|---|
| 명령 타임아웃 | 60초 | Redis 응답을 60초까지 기다린다 |
| 연결 타임아웃 | 10초 | |
| 끊기면 | 자동 재연결 시도, 그동안 명령을 큐에 쌓음 | |

그래서 아무 설정 없이 Redis 를 멈추면 재고 조회 요청이 하나하나 최대 60초 걸린다. 주문 → 재고 호출 읽기 타임아웃이 그보다 짧으니 주문은 502 로 끝난다. Spring Boot 의 `spring.data.redis.timeout` 으로 줄일 수 있다.

`@Cacheable` 을 쓸 때 캐시가 실패하면 기본 동작은 **예외를 다시 던지는 것**(`SimpleCacheErrorHandler`)이다. "캐시가 죽으면 DB 로 가라" 를 하려면 오류 처리기를 바꿔 끼워야 한다(`LoggingCacheErrorHandler`). `RedisTemplate` 을 직접 쓰면 try/catch 로 직접 한다.

### 2.6 헬스체크와 부딪치는 곳 : actuator 가 Redis 에 INFO 를 보낸다

이번 작업에서 가장 놓치기 쉬운 부분이다.

- Spring Boot 는 Redis 라이브러리(`spring-boot-starter-data-redis`)가 들어오면 `/actuator/health` 에 Redis 확인을 **자동으로** 붙인다(`RedisHealthIndicator`)
- 확인할 때 Redis 에 `INFO` 명령을 보낸다. 우리 Boot 3.5.16 jar 를 열어 `RedisServerCommands.info()` 를 부르는 것을 확인했다(조사 답은 처음에 PING 이라고 물었고 INFO 로 바로잡혔다)
- 에이전트는 이 `INFO` 를 Redis CLIENT 스팬으로 기록한다. 부모는 `/actuator/health` SERVER 스팬이다(추정, 5 절에서 확인)
- 그런데 backend Collector 는 `#92`(ADR `#50`)로 **헬스체크 SERVER 스팬만** 버린다. 자식인 `INFO` 스팬은 남아 **부모 없는 스팬(고아)** 이 되고, compose 헬스체크가 5초마다 부르니 Service map 에 `inventory → redis` 간선이 손님 없이도 생긴다
- ADR `#50` 의 되돌리는 조건이 바로 이것이다 : "헬스체크가 실제 쿼리나 호출을 하게 되면(헬스체크에 Redis 확인 추가) 자식 CLIENT 스팬이 생겨 다시 고른다"

MySQL 은 이 문제가 없다. actuator 의 DB 확인은 쿼리 대신 `Connection.isValid()` 를 쓰고, 실데이터에서 헬스체크 트레이스 57개 = 스팬 57개였다(자식 없음, ADR `#50`).

끄는 설정은 `management.health.redis.enabled=false` 한 줄이다. 끄면 Redis 가 죽어도 헬스체크는 UP 이 된다.

### 2.7 결제가 실패하면 줄인 재고는

재고를 먼저 줄이고 결제가 실패하면, 그 재고는 팔리지 않았는데 줄어든 채로 남는다. 되돌리는 방법은 "결제 실패를 받은 주문이 재고 서비스에 '다시 더해 줘' 를 부르는 것"(보상 호출)이다. 같은 주문으로 두 번 더해지지 않게 주문 번호로 막아야 한다.

안 하면 k6 로 결제 장애를 섞어 돌릴 때 재고가 계속 줄어 0 이 되고, 그 뒤 주문은 결제 장애가 아니라 **재고 부족 409** 로 실패한다. 화면에서는 원인이 바뀌어 보인다.

### 2.8 데이터는 어디로 흐르나

```
k6 · Watchdog ──▶ gateway ──▶ order ──(1) 재고 차감──▶ inventory ──▶ MySQL stock 표
                              │                         └──(조회)──▶ Redis (캐시)
                              ├──(2) 재고 있으면 결제──▶ payment ──▶ pg-stub
                              └──(3) 결제 실패면 재고 복원? (2.7, 정할 것)

각 서비스 에이전트 ──▶ Collector ──▶ Kafka raw ──▶ Ingester ──▶ ClickHouse
   server_map_1m : order→inventory (SERVICE), inventory→mysql:3306 (DB), inventory→redis:6379 (DB)
```

| 질문 | 답 |
|---|---|
| 재고의 정본은 어디인가 | MySQL (캐시는 복사본) |
| Redis 가 비거나 죽으면 | 정할 것 (DB 로 가나, 에러를 내나 : 2.5) |
| 재고는 누가 채우나 | 정할 것 (앱이 뜰 때 넣기 · k6 전에 넣기) |

### 2.9 자주 헷갈리는 것

| 헷갈리는 것 | 실제 |
|---|---|
| Service map 에 "캐시" 칸이 따로 생긴다 | 아니다. `db.system=redis` 라 MySQL 과 같은 DB 칸이다 |
| Redis 도 `peer-service-mapping` 에 넣어야 이름이 예쁘게 나온다 | 넣으면 SERVICE 로 잘못 잡힌다. 넣는 건 `inventory=shop-inventory` 하나 |
| 헬스체크 필터(`#92`)가 있으니 헬스체크 스팬은 다 사라진다 | 부모(SERVER)만 사라진다. actuator 가 Redis 에 보내는 `INFO` 자식 스팬은 남는다 |
| actuator 는 Redis 에 PING 을 보낸다 | INFO 다 (Boot 3.5.16 jar 확인) |
| `@Cacheable` 을 쓰면 화면에 캐시 히트가 보인다 | 아니다. 밑의 Redis 명령 스팬만 보인다 |
| Redis 가 죽으면 바로 에러가 난다 | 기본은 60초까지 기다린다 |
| 캐시가 있으니 재고 차감도 Redis 에서 한다 | 흔한 구성은 "조회만 캐시, 차감은 DB". Redis 차감은 빠르지만 DB 와 어긋날 수 있다 |

---

## 3. 1차 질문 (사람 차례)

> 승조가 2 를 읽고 모르는 것을 여기에 적는다. 답은 바로 아래에 붙인다.
> **비어 있으면 아직 안 물은 것이다. 대신 채우지 않는다.**

- **Q. (내 정리) 이번 작업은 재고 서비스를 만들고, 재고 서비스가 MySQL 과 Redis 를 쓴다. 흐름은 주문 → 바로 결제였던 것을 주문 → 재고가 있어야 결제로 바꾼다. 맞나**
  - A. 맞다.
- **Q. Service map 이 Redis 를 무엇으로 보나 (2.2) 를 쉽게**
  - A. Service map 의 화살표는 "부른 쪽" 이 남긴 CLIENT 스팬(내가 남을 호출했다는 기록)으로만 그린다. 화살표 끝에 무엇을 둘지는 스팬의 Attribute 로 정한다 : `peer_service`(상대가 우리 서비스라는 이름표)가 있으면 우리 서비스, `db.system`(상대가 DB 라는 표시)이 있으면 DB, 둘 다 없으면 외부. 에이전트는 Redis 호출에 `db.system=redis` 를 붙이므로 Redis 는 MySQL 처럼 DB 로 그려진다. 주의 두 가지 : `peer-service-mapping` 에 redis 를 적으면 `peer_service` 가 먼저 보여 Redis 가 "우리 서비스" 로 잘못 그려진다. 그리고 에이전트가 3.0 으로 올라가면 Attribute 이름이 `db.system.name` 으로 바뀌는데 Service map SQL 은 옛 이름만 보므로 MySQL · Redis 가 외부로 떨어진다(백엔드에 알릴 일).
- **Q. 동시성을 막는 방법 넷의 설명과 장단점** → 5 절 표에 둔다. 요약 : 조건부 UPDATE(한 줄로 확인+차감, 가장 작다) · FOR UPDATE(읽으며 잠금, 대기 발생) · 버전 컬럼(잠금 없이 충돌 시 재시도) · Redis 차감(가장 빠르지만 DB 와 어긋날 수 있음)
- **Q. 캐시 방식 셋의 장단점** → 5 절 표에 둔다. 요약 : cache-aside(단순, 첫 조회 느림, 지우는 시점 주의) · write-through(캐시 항상 최신, 쓰기 느림) · write-behind(쓰기 빠름, Redis 가 죽으면 잃음)
- **Q. 우리는 Lettuce 를 쓰나. 버전은. `spring.data.redis.timeout` 으로 60초를 줄일 수 있나**
  - A. 지금은 Redis 의존성이 없어 안 쓴다. `spring-boot-starter-data-redis` 를 넣으면 기본 클라이언트가 Lettuce 이고, 버전은 Boot 3.5.16 이 정한 **6.6.0.RELEASE** 다(BOM 확인). 맞다 : `spring.data.redis.timeout` 이 명령 하나의 응답 대기 시간이고, 예를 들어 `500ms` 로 주면 60초 대신 0.5초 뒤 실패한다. 연결 대기는 `spring.data.redis.connect-timeout` 으로 따로 준다.
- **Q. `@Cacheable` 은 무엇인가**
  - A. 메서드에 붙이는 Spring 애노테이션으로 "같은 인자로 다시 부르면 메서드를 실행하지 말고 캐시에 있는 결과를 돌려줘라" 는 뜻이다. `@Cacheable("stock") fun find(productId)` 면 처음엔 DB 를 읽고 결과를 Redis 에 넣고, 다음부터는 Redis 값을 준다. 값을 바꿀 땐 `@CacheEvict` 로 지운다. cache-aside 를 코드 없이 해 주는 도구다.
- **Q. actuator 는 무엇인가**
  - A. Spring Boot 앱의 상태를 HTTP 로 보여 주는 내장 기능이다. `/actuator/health` 는 "살아 있나(UP/DOWN)", 그 밖에 지표 · 설정 등을 보여 준다. 우리 compose · CI 가 5초마다 `curl /actuator/health` 로 준비됐는지 확인하는 그 주소다. 앱이 DB · Redis 를 쓰면 health 가 그것까지 확인해 하나라도 죽으면 DOWN 이 된다.
- **Q. 헬스체크 때 스팬이 어떻게 생기나. DB 와 Redis 의 차이는** (예시)
  - A. compose 가 `GET /actuator/health` 를 부르면 트레이스가 이렇게 생긴다.
    - MySQL 만 쓰는 order : `[SERVER] GET /actuator/health` 하나뿐. DB 확인은 `Connection.isValid()` 라 SQL 을 실행하지 않고, 에이전트는 SQL 실행만 스팬으로 남기므로 자식이 없다. Collector 가 이 SERVER 를 버리면 트레이스가 통째로 사라진다
    - Redis 를 쓰는 inventory : `[SERVER] GET /actuator/health` 아래에 `[CLIENT] INFO (db.system=redis)` 가 붙는다. Redis 확인은 진짜 명령(INFO)을 보내고, 에이전트는 Redis 명령을 전부 스팬으로 남기기 때문이다. Collector 는 SERVER 만 버리므로 `INFO` 가 부모 없이 남고, Service map 집계는 CLIENT 스팬을 세니 `inventory → redis:6379` 화살표가 5초마다 1건씩 생긴다
- **Q. 결제가 될 때까지 PENDING 으로 두고, 결제가 되면 재고를 줄이면 안 되나**
  - A. 된다(결제 후 차감). 다만 되돌리는 일이 재고에서 **결제로** 옮겨 간다. 재고 1개에 두 주문이 동시에 오면 둘 다 결제가 되고, 나중에 차감할 때 한 명은 재고가 없다. 그러면 이미 받은 돈을 환불(결제 취소 호출)해야 하고, 외부 결제사 환불은 재고 되돌리기보다 무겁고 실패할 수도 있다. 그래서 보통은 결제 전에 줄이거나 "예약"(잠시 잡아 두기)하고, 결제가 실패하면 되돌린다. 5 절 선택지에 넣는다.
- **Q. 자주 헷갈리는 것(2.9)을 하나씩**
  - A. 대화 답에 풀어 두었다. 핵심은 세 가지 : Redis 는 DB 칸으로 그려진다, 헬스체크 필터는 부모만 버린다, Redis 가 죽으면 기본 60초를 기다린다.

- **Q. (2차 정리) `peer_service` 는 CLIENT 스팬이 우리 서비스를, `db.system` 은 DB 를 부른다는 표시이고 Redis 는 `db.system=redis` 라 DB 로 본다. `inventory=shop-inventory` 를 적어 재고 서비스가 스팬에 들어오게 하는 것인가. 에이전트 3.0 은 무엇인가**
  - A. 앞부분은 맞다(`db.system` 은 "우리" DB 가 아니라 "상대가 DB" 라는 뜻). `inventory=shop-inventory` 는 **order** 에 적는다 : order 가 `inventory:8093` 을 부른 CLIENT 스팬에 `peer_service=shop-inventory` 가 붙어 화살표 끝이 "우리 서비스 shop-inventory" 가 된다. 안 적으면 `inventory:8093` 이라는 외부 노드가 된다(`#23` 의 payment 와 같은 문제). 재고 서비스 자신의 스팬은 에이전트만 붙으면 들어온다. 에이전트 3.0 은 지금 쓰는 OTel Java Agent 2.31.1 의 다음 큰 버전(추정)으로, Redis 스팬의 Attribute 가 `db.system=redis` → `db.system.name=redis` 로 바뀐다. Service map SQL 이 `db.system` 만 보면 그날부터 `redis:6379` 가 외부로 떨어진다.
- **Q. 조건부 UPDATE 에서 "몇 개 남았나" 를 따로 읽는다는 건 SELECT 가 필요하다는 건가. `SELECT ... FOR UPDATE` 는 무엇인가**
  - A. 그렇다. UPDATE 는 "바뀐 행 수" 만 돌려주므로 남은 수량을 응답에 넣으려면 SELECT 를 한 번 더 한다(필요 없으면 안 해도 된다). `SELECT qty FROM stock WHERE product_id=? FOR UPDATE` 는 읽으면서 그 행에 **배타 행 락**을 건다. 트랜잭션이 끝날(커밋 · 롤백) 때까지 다른 트랜잭션은 같은 행을 읽어 잠그거나 바꾸지 못하고 기다린다. 앱이 qty 를 보고 판단한 뒤 UPDATE 하고 커밋하면 락이 풀린다. 트랜잭션(`@Transactional`) 안에서만 의미가 있다.
- **Q. 대규모 · 핫 상품이면 Redis 차감 + SETNX 인가. 표시용은 cache-aside 가 맞나. write-behind 는 위험하고 write-through 는 한쪽만 성공하는 문제가 있다**
  - A. 핫 상품 대량 트래픽이면 Redis 원자 차감(`DECRBY` 또는 Lua 스크립트)이 흔하다. SETNX 는 차감이 아니라 **분산 락**(먼저 잡은 한 명만 진행)을 거는 명령이라, 락 + DB 차감은 결국 한 줄로 세우는 방식이라 더 느리다. 표시용 조회는 cache-aside 가 맞다. write-behind 판단도 맞다. write-through 는 순서를 DB 먼저 → 캐시로 하면, 캐시 쓰기가 실패해도 정본(DB)은 맞고 캐시만 낡는다. TTL 이나 다음 갱신으로 회복되므로 생각보다 덜 위험하다. 반대 순서(캐시 먼저)일 때가 위험하다.
- **Q. Lettuce 는 Redis 가 죽으면 요청당 60초를 기다리니 `spring.data.redis.timeout` 을 50ms 로 줘서 빨리 실패하게 하는 것인가**
  - A. 맞다. 다만 50ms 는 빡빡하다. 로컬 Redis 명령은 1ms 안쪽이지만 GC 멈춤 · 네트워크 흔들림에 정상 요청까지 실패할 수 있어 보통 수백 ms(예 : 300 ~ 500ms)를 준다. 값은 5 절에서 정한다.
- **Q. `@CacheEvict` 는 같은 인자로 다시 부르면 함수를 실행하는 것인가**
  - A. 아니다. `@CacheEvict` 가 붙은 메서드는 **항상 실행**되고, 실행 뒤 해당 키의 캐시를 지운다. "실행을 건너뛰는" 것은 `@Cacheable` 이다(캐시에 있으면 건너뜀).
- **Q. `/actuator/health` 는 compose · CI 에 하트비트를 보내 UP/DOWN 을 표시하나**
  - A. 방향이 반대다. 앱이 보내는 게 아니라 compose · CI 가 5초마다 앱에 `GET /actuator/health` 를 **물어보고**, 앱이 `{"status":"UP"}` 으로 답한다(당겨 오기). 하트비트는 앱이 스스로 "살아 있다" 를 보내는 것이라 다르다.
- **Q. `Connection.isValid()` 와 Redis `INFO` 는 무엇인가**
  - A. `isValid()` 는 JDBC 표준 메서드로 "이 DB 연결이 아직 살아 있나" 를 드라이버에 묻는다. MySQL 드라이버는 SQL 이 아니라 가벼운 ping 패킷을 보낸다. 에이전트는 SQL 실행만 스팬으로 남기므로 스팬이 안 생긴다. `INFO` 는 Redis 명령으로 서버 정보(버전 · 메모리 · 접속 수 등)를 글로 돌려준다. 보통 명령이라 에이전트가 스팬으로 남긴다.
- **Q. 헬스체크가 DB 를 볼 땐 CLIENT 스팬이 안 생기고 Redis 를 볼 땐 생기는데, Collector 가 SERVER 를 지우니 orphan span 이 되는 것인가**
  - A. 맞다. 헬스체크 요청 자체가 SERVER 스팬이고, DB 확인은 자식을 안 만들고 Redis 확인은 자식 CLIENT(`INFO`)를 만든다. Collector 가 SERVER 만 지우니 `INFO` 가 부모 없이 남는다.
- **Q. 결제가 될 때까지 재고를 안 줄인 채(PENDING) 두고, 결제가 되면 줄이면 안 되나**
  - A. 할 수는 있지만 PENDING 동안 다른 주문도 같은 재고를 "있다" 로 본다. 재고 1개에 두 주문이 동시에 결제까지 가면 한 명은 결제 뒤 차감에서 실패하고, 그때는 환불(외부 결제사 취소)이 필요하다. 이 생각을 살리는 방법이 **예약**이다 : 재고(qty)는 그대로 두고 `reserved` 를 늘려 "팔 수 있는 수 = qty - reserved" 로 막고, 결제 성공이면 qty 를 줄이며 예약을 풀고, 실패면 예약만 푼다. 5 절에서 선차감+복원 · 예약 · 결제 후 차감을 비교한다.
- **Q. `@Cacheable` 을 쓰면 캐시 히트가 보이나 (자세히)**
  - A. 직접 표시는 없고 모양으로 짐작만 된다. 히트면 트레이스에 `[CLIENT] GET (redis)` 하나, 미스면 `GET (redis)` → `SELECT (mysql)` → `SET (redis)` 셋이 보인다. 하지만 "cache.hit=true" 같은 Attribute 가 없어서 히트율을 집계할 수 없다. 보이게 하려면 앱 코드에서 스팬에 속성을 직접 달아야 하는데, ADR `#33`(앱 코드는 에이전트를 모른다)과 부딪친다.

---

## 4. 1차 정리

3 의 질문 스무 개를 거친 뒤 확실해진 것만 묶는다.

- 확실한 것 :
  - 주문 흐름이 "주문 → 재고 확인 · 차감 → 재고가 있을 때만 결제" 로 바뀌고, 재고 부족은 409 다
  - Service map 은 Redis 를 `db.system=redis` 로 보고 DB 칸에 그린다. order 에 `inventory=shop-inventory` 를 적어야 order → 재고 화살표가 우리 서비스로 이어지고, Redis 는 매핑에 넣지 않는다
  - 재고 정본은 MySQL 이고, Redis 는 조회용 복사본으로 쓰는 것이 흔한 구성이다
  - actuator 가 `/actuator/health` 때 Redis 에 `INFO` 를 보내고, Collector 는 헬스체크 SERVER 만 버리므로 `INFO` 가 고아로 남는다
  - Lettuce 6.6.0 기본 명령 타임아웃은 60초, `spring.data.redis.timeout` 으로 줄인다
  - 결제 실패 때 재고를 되돌리지 않으면 장애 주입 중 재고가 0 이 되어 원인이 바뀌어 보인다. "결제 후 차감" 은 되돌림이 환불로 옮겨 가고, 그 생각을 살리는 길이 "예약" 이다
- 아직 모르는 것 (5 에서 확인) :
  - `INFO` 가 정말 헬스체크의 자식으로 붙나 (추정이었다)
  - Redis 를 멈추면 실제로 몇 초 걸리나, 타임아웃을 줄이면 몇 초인가
  - 캐시 히트 · 미스가 트레이스에서 어떻게 보이나
  - 무엇을 고를지 : 차감 방식, 캐시 방식, 결제 실패 처리, Redis 헬스체크, Redis 장애 처리, 장애 주입

---

## 5. 선택지와 설명

### 조사 프롬프트

원문은 같은 폴더의 [`prompts.md`](prompts.md) 에.

### 우리 데이터로 확인한 것 (먼저 본다)

빈 `inventory` 를 복사해 Redis starter 와 시험용 API(`GET /probe/{k}` : Redis 에 없으면 `SET`) 하나를 넣고, 에이전트 2.31.1 을 붙여 `redis:7.2` 와 같이 띄웠다. 스팬은 화면 출력(`logging-otlp`)으로 읽었다. 레포 브랜치는 건드리지 않았다.

| 확인 | 값 |
|---|---|
| Boot 3.5.16 이 넣는 Lettuce | `lettuce-core-6.6.0.RELEASE` |
| Lettuce 6.6.0 `RedisURI.DEFAULT_TIMEOUT` | `60` (초). jar 에서 직접 확인 |
| `/actuator/health` 응답 | `"redis":{"status":"UP","details":{"version":"7.2.16"}}` : Redis 확인이 자동으로 붙었다 |
| 헬스체크 트레이스 | `[SERVER] GET /actuator/health` 의 자식으로 `[CLIENT] INFO  db.system=redis  db.statement="INFO server"`. **추정이 맞았다** |
| 연결을 맺을 때 | `HELLO 3` · `CLIENT SETINFO lib-name Lettuce` · `CLIENT SETINFO lib-ver …` 세 개가 **부모 없는** CLIENT 스팬으로 생긴다. 조사 때 몰랐던 것. 연결이 새로 맺힐 때만(앱 시작 · 재연결) 생긴다 |
| 캐시 미스 (`/probe/P-100` 첫 호출) | 한 트레이스에 `GET P-100` → `SET P-100 ?` (값은 `?` 로 가려짐) |
| 캐시 히트 (두 번째 호출) | `GET P-100` 하나 |
| Redis 를 멈추고 기본값 그대로 | 조회 요청 **60.06초** 뒤 500, 헬스체크 **60.13초** 뒤 503 |
| Redis 를 멈추고 `spring.data.redis.timeout=500ms` | 조회 **0.56초** 뒤 500, 헬스체크 **0.62초** 뒤 503 |

Redis 를 멈췄을 때 기본값이면 order 의 재고 호출 읽기 타임아웃(지금 payment 호출은 5초)이 먼저 끊겨 주문이 502 가 되고, 재고 서비스 스레드는 60초씩 붙잡힌다.

### 나온 선택지

결정할 것이 여섯 가지이다.

#### ⓐ 재고를 줄이는 방법

| 방법 | 얻는 것 | 포기하는 것 | 구현 크기 |
|---|---|---|---|
| **a1. 조건부 UPDATE** (`… WHERE qty >= ?`) | SQL 한 줄로 확인 + 차감, 정확, 트랜잭션 코드 불필요 | 남은 수량을 응답하려면 SELECT 한 번 더. 대기가 거의 없어 "락 경합" 신호는 안 나온다 | 작다 |
| a2. `SELECT … FOR UPDATE` | 정확, 읽은 값으로 판단 가능. 핫 상품에서 락 대기가 생겨 데모 신호가 된다 | `@Transactional` 필요, 대기 줄 | 중간 |
| a3. 버전 컬럼 (낙관적 락) | 평소 대기 없음 | 충돌 시 재시도 코드, 동시 주문이 많으면 실패 급증 | 중간 |
| a4. Redis 차감 (`DECRBY` · Lua) | 가장 빠름, Service map 에 Redis 쓰기가 크게 보인다 | Redis · DB 불일치, Redis 가 죽으면 차감 불가, 보정 로직 필요 | 크다 |

| 방법 | 정확한가 | 동시 주문 많을 때 | Redis 가 죽으면 | 데모 신호 |
|---|---|---|---|---|
| **a1** | 예 (DB 행 락) | 짧은 행 락만 | 차감은 계속 됨 | 약함 |
| a2 | 예 | 대기 줄 → 지연 증가 | 계속 됨 | 락 대기 (OTel Demo `productCatalogLockContention` 과 같은 종류) |
| a3 | 예 | 재시도 · 실패 증가 | 계속 됨 | 실패율 |
| a4 | 보정 필요 | 가장 잘 버팀 | 멈춤 | Redis 쓰기 |

a1 은 "확인하고 줄이기" 를 MySQL 이 한 번에 하므로 다른 코드가 필요 없다. 재고가 모자라면 바뀐 행이 0 이라 그대로 409 를 내면 된다. a2 는 같은 정확성을 얻지만 트랜잭션 동안 행을 잡고 있어 동시 주문이 몰리면 줄이 선다. 그 줄 자체가 "DB 락 경합" 이라는 장애 신호라는 점이 장점이자 단점이다. a3 은 잠그지 않는 대신 실패를 앱이 다시 시도해야 해서, 더미 쇼핑몰에 쓰기엔 코드가 늘어난다. a4 는 대규모 핫 상품의 정석에 가깝지만, 재고 정본이 Redis 가 되면 "Redis 장애 = 주문 불가" 가 되고 DB 와 맞추는 일이 따로 생긴다. 컬리 · 올리브영 사례는 Redis 분산 락이나 Redis 재고 + 장애 시 DB 전환을 쓴다(5 절 「남이 어떻게 하나」).

#### ⓑ 캐시를 어디에 어떻게

| 방법 | 얻는 것 | 포기하는 것 | 구현 크기 |
|---|---|---|---|
| **b1. 조회만 cache-aside** (`GET /api/stock/{id}` 를 캐시, 차감 뒤 커밋하고 evict) | 단순, Redis 가 죽어도 DB 로 갈 수 있음 | 주문 경로(차감)에는 Redis 가 안 나온다 : 손님이 조회를 불러야 Redis 화살표가 생긴다 | 작다 |
| b2. 주문 경로에서도 캐시 조회 (차감 전에 캐시로 "있나" 먼저 보고, 없으면 바로 409) | 주문 한 건마다 Redis 가 트레이스에 나온다. 캐시가 0 이면 DB 를 안 찌른다 | 캐시가 낡으면 실제로 있는데 409 · 없는데 차감 시도. 결국 차감은 DB 가 다시 확인 | 작다 |
| b3. write-through | 캐시 늘 최신 | 쓸 때마다 두 곳, 순서 · 한쪽 실패 처리 | 중간 |
| b4. write-behind | 쓰기 가장 빠름 | Redis 가 죽으면 잃음 | 크다 |

| 방법 | 주문 트레이스에 Redis 가 보이나 | 캐시가 낡으면 | 도구 |
|---|---|---|---|
| **b1** | 아니오 (조회 API 에서만) | 표시만 잠깐 틀림 | `@Cacheable` · `@CacheEvict` 또는 `RedisTemplate` |
| b2 | 예 | 잘못된 409 가 날 수 있음 (DB 가 최종 확인하면 차감 쪽은 안전) | 같음 |
| b3 | 예 | 거의 안 낡음 | `RedisTemplate` |
| b4 | 예 | 정본이 Redis | `RedisTemplate` + 배치 |

도구는 `@Cacheable` 을 써도 `RedisTemplate` 을 써도 트레이스에는 똑같이 `GET` · `SET` · `DEL` 만 보인다(위 표에서 확인). 차이는 코드 양과 TTL · 오류 처리를 얼마나 직접 쥐느냐다. `@Cacheable` 은 캐시 실패 때 기본으로 예외를 다시 던져서, "Redis 가 죽으면 DB 로" 를 하려면 `LoggingCacheErrorHandler` 를 끼워야 한다.

b1 은 정석이지만 Service map 의 `inventory → redis` 화살표가 조회 API 가 불릴 때만 생긴다. k6 시나리오에 조회를 섞거나(주문 전에 상품 재고를 보는 손님), 주문 경로에서 캐시를 한 번 읽게(b2) 해야 화살표가 늘 보인다.

#### ⓒ 결제가 실패하면 재고는

| 방법 | 얻는 것 | 포기하는 것 | 구현 크기 |
|---|---|---|---|
| **c1. 선차감 + 복원** (결제 실패 시 order 가 `POST /restore`, 주문 번호로 두 번 복원 막기) | 흔한 단순 사가. 재고가 정확히 돌아온다 | 복원 호출도 실패할 수 있다(그땐 재고가 줄어 남음). 복원 API · 멱등 표 필요 | 중간 |
| c2. 예약 (`reserved` 를 늘리고 결제 결과로 확정 · 해제) | 승조 제안(PENDING)을 살린다. 초과 판매 차단 | 확정 · 해제 두 API, 결제 응답 없이 끊기면 예약이 남음(만료 처리 필요) | 중간 ~ 크다 |
| c3. 결제 후 차감 | 재고 코드가 가장 단순 | 동시 주문 시 초과 판매 → 환불(외부 결제사 취소) 필요. pg-stub 에 취소 API 가 없다 | 작다 + 환불 |
| c4. 되돌리지 않음 | 가장 작음 | 결제 장애 주입 중 재고가 0 으로 수렴, 이후 모든 주문이 409 로 원인이 바뀐다 | 없음 |

| 방법 | 결제 장애를 섞은 k6 를 오래 돌리면 | 끊김(타임아웃) 때 |
|---|---|---|
| **c1** | 재고 유지 | 결제 결과를 모르면 복원할지 애매 |
| c2 | 재고 유지 | 예약이 남아 만료가 필요 |
| c3 | 초과 판매 시 환불 | 결제는 됐는데 차감 실패 |
| c4 | 재고 0 → 전부 409 | — |

c4 를 고르더라도 "k6 를 돌리기 전에 재고를 크게 채워 두기" 로 버틸 수는 있다. 다만 오래 돌리면 결국 0 이 된다.

#### ⓓ actuator 의 Redis 헬스체크

| 방법 | 얻는 것 | 포기하는 것 |
|---|---|---|
| **d1. 끈다** (`management.health.redis.enabled=false`) | `INFO` orphan span 이 사라진다. Service map 이 손님 요청만 센다. 백엔드 ADR `#50` 을 다시 열 필요 없음 | Redis 가 죽어도 재고 서비스 헬스체크는 UP. 컨테이너가 Redis 장애로 재시작되지 않는다(캐시라 오히려 맞을 수 있음) |
| d2. 켜 두고 백엔드가 트레이스 단위로 거른다 | 헬스체크가 Redis 상태까지 반영 | 백엔드 ADR `#50` 재결정(트레이스 기억 버퍼). 쇼핑몰 이슈가 백엔드 일을 만든다 |
| d3. 켜 두고 둔다 | 아무 일 안 함 | 5초마다 `inventory → redis` 화살표 +1, 손님 0명이어도 Redis 호출 수가 쌓인다 |

연결을 맺을 때 생기는 `HELLO` · `CLIENT SETINFO` orphan span 은 d1 로도 없어지지 않는다. 앱 시작 · 재연결 때 3개씩이라 양은 작다. 받아들이거나, 백엔드에 "부모 없는 Redis 연결 설정 명령" 을 알리는 정도로 둔다.

#### ⓔ Redis 가 죽으면

| 방법 | 얻는 것 | 포기하는 것 |
|---|---|---|
| **e1. 타임아웃을 짧게(예 : 300 ~ 500ms) + DB 로 폴백** | 주문은 계속 된다. 화면에는 "Redis 에러 스팬 + 재고 서비스 지연 증가" 가 남는다 | 폴백 코드(`LoggingCacheErrorHandler` 또는 try/catch) |
| e2. 타임아웃을 짧게 + 에러 | 원인이 뚜렷이 보인다(재고 500 → 주문 502) | Redis 가 캐시인데 캐시 장애로 주문이 멈춘다 |
| e3. 기본값(60초) | 아무 일 안 함 | 확인한 대로 요청마다 60초, order 쪽이 먼저 끊겨 502. 재고 서비스 스레드가 묶인다 |

ⓑ 에서 b1(조회만 캐시)을 고르면 e 는 조회 API 에만 영향이 있다. b2 를 고르면 주문 경로에도 영향이 간다.

#### ⓕ 장애 주입

지금 k6 는 `X-Shop-Fault` 로 `payment-error` · `payment-slow` · `pg-error` · `pg-slow` 넷을 섞는다. 재고 쪽 후보 :

| 후보 | 무엇이 보이나 |
|---|---|
| `inventory-slow` (재고 서비스 n초 지연) | order → inventory 화살표 지연, 콜트리에서 "재고가 느리다" |
| `inventory-error` (재고 500) | 주문 502, 결제 화살표 호출 수 감소 |
| 재고 부족 (재고를 0 으로 둔 상품 `P-SOLDOUT` 주문) | 409 비율. 4xx 알림 규칙 재료 (지금 쇼핑몰은 4xx 를 거의 안 낸다) |
| Redis 정지 (`docker compose stop redis`) | ⓔ 에 따라 지연 · 에러. 헤더로는 못 하고 손으로 |

#### ⓖ 언제 대규모 방식으로 바꾸나

| 방법 | 얻는 것 | 포기하는 것 |
|---|---|---|
| **g1. 더미 기준으로 만들고, 바꾸는 조건을 적어 둔다** | 지금 구현이 작다. 감시 대상 역할(Service map · 장애 신호)에 충분 | 대규모 패턴(원자 차감 · 예약 · 차단기)은 코드로 보여 주지 못한다 |
| g2. 처음부터 대규모 방식 | 정석을 코드로 보여 준다 | Redis 원자 차감 · 예약 만료 · 차단기 · 재시도까지 구현이 커지고, 1b 일정과 스레드 덤프 작업을 밀어낸다 |

비교 표와 바꾸는 조건은 7 절.

### 남이 어떻게 하나

| 도구 · 제품 | 어떻게 하나 | 출처 |
|---|---|---|
| OTel Demo | 재고 서비스는 없다. cart 가 Valkey 를 **주 저장소**로 쓴다(캐시가 아님). 장애 플래그에 `cartFailure` · `productCatalogLockContention`(DB 락 경합) · `recommendationCacheFailure` · `paymentFailure` 등 | https://opentelemetry.io/docs/demo/services/cart/ , https://opentelemetry.io/docs/demo/feature-flags/ |
| Pinpoint quickstart | Tomcat TestApp, Redis · 재고 없음 | https://github.com/pinpoint-apm/pinpoint-docker |
| Spring PetClinic | Redis 가 아니라 프로세스 안 JCache, `vets` 하나 | https://github.com/spring-projects/spring-petclinic |
| 컬리 | Redisson 분산 락. 락 없이 100건 중 21건 갱신 유실을 재현 | https://helloworld.kurly.com/blog/distributed-redisson-lock/ |
| 올리브영 | 재고를 MemoryDB(Redis 호환)에 두고 Redisson 락, 장애 시 CircuitBreaker 로 기존 DB 전환 | https://oliveyoung.tech/2023-10-04/inventory-project/ |
| AWS 캐싱 백서 | cache-aside(lazy loading) · write-through, TTL 권장 | https://docs.aws.amazon.com/whitepapers/latest/database-caching-strategies-using-redis/caching-patterns.html |

### AI 가 틀렸거나 덜 맞았던 것 (남겨 둔다)

- 조사 질문에서 actuator 가 Redis 에 PING 을 보낸다고 전제했다. 실제는 `INFO` 였고(조사 답 + jar 확인), 이번 재현에서 `INFO server` 로 다시 확인했다
- "INFO 가 헬스체크 SERVER 의 자식" 은 조사 단계에서 추정이었다. 재현으로 확인했다
- 조사도 2 절도 연결을 맺을 때의 `HELLO` · `CLIENT SETINFO` orphan span 을 몰랐다. 재현에서 처음 봤다
- 구현에서 `GenericJackson2JsonRedisSerializer()` 를 그대로 썼다. Kotlin data class 를 되읽지 못해 모든 조회가 미스였고, E2E 시험의 "0.04초 = 히트" 도 잘못 읽은 것이었다. 별도 리뷰가 잡았다(prompts.md 「결과」)
- 3 절에서 "write-through 는 한쪽만 성공하면 문제" 라는 승조 정리에, 순서를 DB 먼저로 하면 캐시만 낡는다고 답했다. 이건 일반론이고 출처를 달지 못했다

### 라이브러리 · 레포를 직접 열어 확인한 것

| 확인 | 결과 |
|---|---|
| `spring-boot-actuator-3.5.16.jar` 의 `RedisHealthIndicator` | `RedisServerCommands.info()` 호출 |
| `spring-boot-dependencies-3.5.16.pom` | `lettuce.version` 6.6.0.RELEASE |
| `lettuce-core-6.6.0.RELEASE` `RedisURI` | `DEFAULT_TIMEOUT = 60` |
| backend `db/clickhouse/004_create_materialized_views.sql` | Service map 은 `peer_service` → `attributes['db.system']` → 외부 순으로 가른다 |
| 로컬 ClickHouse Service map | 지금 간선 : gateway → order, order → payment, order → `mysql:3306`(DB), payment → `pg-stub:8080`(외부) |

### 확인 못 한 것

- MySQL `Connection.isValid()` 가 스팬을 안 만드는 것은 ADR `#50` 의 실데이터(57 = 57)로만 안다. 드라이버 수준에서 직접 보지는 않았다
- a2(`FOR UPDATE`) 락 대기가 트레이스에서 어떻게 보이는지 재현하지 않았다
- 이번 재현은 Collector · ClickHouse 를 거치지 않았다. `INFO` orphan span 이 실제 Service map 에 몇 건씩 쌓이는지는 구현 뒤 E2E 시험에서 본다
- redis 대신 valkey 이미지를 써도 스팬이 같을지는 시험하지 않았다(클라이언트 쪽 계측이라 같을 것으로 추정)
- 에이전트 3.0 의 출시 시점과 `db.system.name` 단일화 여부(PR 열린 상태)
## 6. 2차 질문 (사람 차례)

- **Q. write-through 에서 "DB 먼저, 캐시 나중" 이면 덜 위험하다는 것을 예시로**
  - A. 재고 10 → 8 로 줄이는 경우. DB 먼저 : DB 8 성공 → Redis 쓰기 실패 → Redis 는 10 으로 낡지만 정본(DB)은 8 이고, TTL 이 끝나거나 다음 차감 때 Redis 가 다시 맞춰진다. 화면 표시가 잠깐 틀릴 뿐 판매는 DB 가 확인하므로 초과 판매가 없다. 캐시 먼저 : Redis 8 성공 → DB 실패(롤백) → Redis 는 8, DB 는 10. 캐시가 "실제로 일어나지 않은 차감" 을 보여 주고, 캐시로 재고를 판단하면 팔 수 있는 2개를 못 판다. 반대로 증가 쪽이면 없는 재고를 판다. 정본보다 앞서간 값이 남는 쪽이 위험하다.
- **Q. 캐시 히트를 숫자로 보려면 앱 코드에 Attribute 를 달아야 하고 그게 ADR `#33` 과 부딪힌다는 것을 예시로**
  - A. 지금 트레이스에는 Redis 명령 스팬만 있다(히트 `GET` 하나, 미스 `GET` → `SET`). 히트율을 집계하려면 스팬에 `cache.hit=true/false` 같은 값이 있어야 하는데 에이전트는 이걸 모른다(히트인지는 앱이 `GET` 결과를 보고 판단하는 일이라). 그래서 앱이 직접 달아야 한다 : `Span.current().setAttribute("cache.hit", v != null)`. 이 한 줄을 쓰려면 앱이 OTel API(`io.opentelemetry:opentelemetry-api`)에 의존하게 되고, ADR `#33` "앱 코드는 에이전트의 존재를 모른다(비침습)" 를 깬다. 쇼핑몰은 "코드를 안 고쳐도 다 보인다" 를 증명하는 감시 대상이라 이 원칙이 중요하다. 우회로 : 스팬 모양(트레이스 안 `GET` 뒤에 `SET` 이 있나)을 백엔드가 집계하거나, 히트율은 Redis 서버 쪽 지표(`INFO stats` 의 `keyspace_hits` · `keyspace_misses`)로 본다.

---

## 7. 2차 정리

선택지를 다 본 뒤의 상태다. 결정은 8 에서 승조가 글로 내린다.

- 좁혀진 선택지 (더미 쇼핑몰 기준) : ⓐ **a1** 조건부 UPDATE · ⓑ **b1**(조회만 cache-aside, k6 에 재고 조회를 섞음) 또는 **b2**(주문 경로에서도 캐시 조회) · ⓒ **c1** 선차감 + 복원 · ⓓ **d1** Redis 헬스체크 끄기 · ⓔ **e1** 짧은 타임아웃 + DB 로 대신 조회 · ⓕ `inventory-slow` · `inventory-error` · 품절 상품(409) · ⓖ **g1** 더미로 만들고 대규모 전환 조건을 적어 둔다
- 결정을 가르는 기준 : 감시 대상으로서 Service map · 장애 신호가 잘 나오나, 구현 크기(1b 일정 · 스레드 덤프와 겹침), 캐시 장애가 주문을 멈추나, 헬스체크 orphan span 을 만드나
- 무엇을 정해야 하나 :
  - ⓑ b1 과 b2 중 무엇 (Redis 화살표를 주문마다 띄울지)
  - ⓔ 타임아웃 값 (300ms · 500ms)
  - 재고 초기값과 상품 목록 (앱이 뜰 때 넣기, `P-SOLDOUT` 은 0)
  - redis 이미지 (`redis:7.2` BSD 마지막 줄 · valkey)
  - 복원 중복을 막는 방법 (주문 번호 기록 표)

### 더미 쇼핑몰과 대규모 쇼핑몰 비교

전제가 다르다.

| | 지금 (더미 쇼핑몰) | 대규모 가정 |
|---|---|---|
| 목적 | Service map · 장애 신호를 보여 주기 | 실제로 팔고, 틀리지 않고, 멈추지 않기 |
| 트래픽 | k6 초당 몇 건 | 한정 판매 때 한 상품에 초당 수천 건 |
| 서버 수 | 서비스마다 1개 | 서비스마다 여러 대 |
| 실패 비용 | 화면이 조금 틀림 | 초과 판매 → 환불 · 고객 불만 |

| 항목 | 더미 추천 | 대규모 추천 | 바뀌는 이유 · 근거 |
|---|---|---|---|
| ⓐ 차감 | a1 조건부 UPDATE | a4 Redis 원자 차감(Lua) + DB 반영, 또는 Redis 분산 락 + DB 차감 | 한 상품 행에 몰리면 DB 행 락에 줄이 서고 커넥션 풀이 마른다. 컬리는 Redisson 분산 락(락 없이 100건 중 21건 갱신 유실 재현), 올리브영은 재고를 MemoryDB(Redis 호환)에 두고 Redisson 락 |
| ⓑ 캐시 | b1 조회만 캐시 | b1 유지 + 핫 상품만 짧은 TTL. 차감은 a4 가 Redis 를 이미 씀 | 표시용 조회는 낡아도 되니 cache-aside(AWS 캐싱 백서의 lazy loading + TTL). "팔 수 있나" 는 캐시가 아니라 원자 차감이 판단 |
| ⓒ 결제 실패 | c1 선차감 + 복원 | c2 예약(만료 포함) + 비동기 보상 | 결제창에서 몇 분 머무르므로 잡아 두고 시간이 지나면 풀기. 복원이 실패해도 메시지 재시도로 결국 맞춘다(microservices.io Saga). c3 는 초과 판매가 곧 환불이라 클수록 비싸다 |
| ⓓ Redis 헬스체크 | d1 끄기 | d1 유지, liveness · readiness 분리 | 캐시 장애로 앱을 재시작하면 모든 파드가 같이 재시작하는 연쇄 장애. a4 면 Redis 가 정본이라 readiness 에만 넣어 트래픽을 빼는 것을 검토 (일반론, 출처 미확인) |
| ⓔ Redis 장애 | e1 짧은 타임아웃 + DB 폴백 | e1 + 서킷 브레이커 + 동시 요청 제한 | 올리브영은 장애 시 CircuitBreaker 로 기존 DB 전환. Redis 가 죽는 순간 모든 요청이 DB 로 몰려 DB 까지 죽을 수 있다 |
| ⓕ 장애 주입 | slow · error · 품절 | 같고 + 핫 상품 몰림 · Redis 장애 조치(failover) | 대규모의 대표 장애는 핫 키와 캐시 스탬피드. OTel Demo 에도 `productCatalogLockContention` · `recommendationCacheFailure` 가 있다 |

대규모일 때 새로 생기는 것 (대응은 일반론, 이번 조사에서 출처 미확인) :

| 문제 | 무엇인가 | 흔한 대응 |
|---|---|---|
| 핫 키 | Redis 키 하나에 몰려 그 노드만 바쁨 | 재고를 여러 키로 나눔 |
| 캐시 스탬피드 | 인기 키 TTL 이 끝나는 순간 수천 요청이 DB 로 | 갱신 중 락, TTL 에 무작위 오차 |
| Redis · DB 불일치 | a4 에서 Redis 는 줄었는데 DB 반영 실패 | 차감 이벤트 기록 · 재시도 · 주기 대조 |
| 예약 만료 | 결제창 이탈 예약이 남음 | TTL 키 · 배치 해제 |
| 대기열 | 한정 판매 입장 제한 | 가상 대기실 |

### 대규모로 바꾸는 조건 (g1 을 고르면 함께 적는다)

| 무엇이 보이면 | 무엇으로 바꾸나 |
|---|---|
| k6 로 핫 상품 하나에 몰았을 때 재고 서비스 P95 가 DB 락 대기로 크게 늘고, 커넥션 풀 대기가 생긴다 | ⓐ a1 → a4(Redis 원자 차감) 또는 a2 + 분산 락 |
| 결제 단계가 길어져(실제 PG · 3DS 인증) 재고를 잡아 두는 시간이 필요해진다 | ⓒ c1 → c2 예약 + 만료 |
| 복원 호출 실패로 재고가 어긋난 주문이 실제로 생긴다 | ⓒ 동기 복원 → 메시지 재시도 (Kafka 도입과 같이) |
| 재고 서비스가 여러 대가 되고 Redis 장애 때 DB 부하가 문제가 된다 | ⓔ e1 + 서킷 브레이커 · 동시 요청 제한 |

### 여기서 나온 면접 질문

1. 재고 차감에서 동시에 두 명이 사면 어떻게 막았나. 왜 `FOR UPDATE` 나 Redis 차감이 아니라 조건부 UPDATE 인가
2. 결제가 실패하면 줄인 재고는 어떻게 되나. "결제 후 차감" 은 왜 안 했나
3. Redis 가 죽으면 주문은 어떻게 되나. 기본 설정이면 몇 초 걸리나
4. 헬스체크가 Redis 를 확인하면 모니터링 데이터에 무슨 일이 생기나. 왜 껐나
5. Service map 에서 Redis 는 왜 DB 로 그려지나. 에이전트를 올리면 무엇이 깨지나
6. 대규모 쇼핑몰이었다면 무엇을 바꾸나. 언제 바꾸나

---

## 8. 구현 프롬프트 (사람 차례)

> 승조가 7 을 읽고 쓴 결정 원문이다. 초안에서 AI 가 두 군데("스레드 풀 고갈" → "커넥션 풀 고갈", 결제 후 차감을 뺀 이유)와 재고 초기값 한 줄을 제안했고 승조가 받아들였다. 같은 원문이 [`prompts.md`](prompts.md) 에 있다. 캐시는 b1 이다("b1으로 가는거지" 확인).

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
