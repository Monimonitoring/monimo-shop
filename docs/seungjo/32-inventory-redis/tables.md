# 표 영향 : `#32` 재고 서비스 + Redis

> 컬럼 정본은 `inventory/src/main/resources/schema.sql` 이다. 여기는 **왜 이 표를 이 자리에서 만들었나** 와 Redis 키를 어떻게 설계했나만 적는다.

## MySQL (DB `shop`, 주문 서비스와 같은 DB · 다른 표)

| 표 | 주인 | 왜 여기서 | 고장 나면 |
|---|---|---|---|
| `stock` | inventory | 재고의 정본. 주문 서비스가 직접 읽지 않고 inventory API 로만 간다 (서비스끼리 표를 공유하지 않는다) | MySQL 이 죽으면 차감이 안 되어 주문이 502 `inventory error`. 캐시가 살아 있어도 조회만 되고 주문은 안 된다 |
| `stock_deductions` | inventory | 주문 번호(`order_id` PK)별 차감 기록. 같은 주문이 두 번 차감되지 않게, 복원이 두 번 더해지지 않게(`restored`) 막는 열쇠 | 이 표 없이 `stock` 만 있으면 주문 서비스가 재시도할 때 두 번 줄고, 복원이 두 번 오면 두 번 는다 |

`stock` 은 `product_id` 가 PK 라 조건부 UPDATE(`WHERE product_id = ? AND qty >= ?`)가 행 하나만 잠근다. 상품마다 행이 다르니 다른 상품 주문끼리는 기다리지 않는다.

`orders.status` 에 `SOLD_OUT` 값이 늘었다 (표 구조는 그대로, VARCHAR 값만). 재고가 모자라 결제를 부르지 않은 주문이다.

## Redis (캐시, 데이터를 남기지 않는다)

### 키 설계

| 키 | 타입 | 값 | TTL |
|---|---|---|---|
| `stock::P-100` | String | `{"productId":"P-100","qty":999998}` (JSON) | 30초 |

키 하나뿐이다. 모양은 Spring Cache 의 기본 규칙 **`<캐시 이름>::<키>`** 그대로다 : 캐시 이름 `stock`, 키는 `@Cacheable(key = "#productId")` 의 상품 번호.

### 왜 이렇게만 했나

- **사용자 ID 별 키가 없다.** 재고는 상품에 속하지 사용자에 속하지 않는다. 누가 조회해도 같은 값이라 상품 번호 하나로 충분하다
- **Hash · Set 같은 다른 타입을 쓰지 않는다.** 캐시하는 값이 "상품 하나의 수량" 하나라 String 이면 된다. Hash(`HSET stock P-100 qty 999998`)로 하면 필드 단위로 읽을 수 있지만 Spring Cache 가 String 으로 넣고 빼므로 그 길에서 벗어날 이유가 없다
- **와일드카드(`KEYS stock::*`)를 쓰지 않는다.** 지울 때는 상품 번호를 알고 있어서 `DEL stock::P-100` 한 건이면 된다. `KEYS` 는 키가 많아지면 Redis 를 멈추게 하는 명령이라 프로덕션에서 금기다
- **값이 JSON 인 이유** : 기본은 JDK 직렬화라 `redis-cli` 로 읽을 수 없다. `Jackson2JsonRedisSerializer(jacksonObjectMapper(), StockResponse)` 로 바꿔 `redis-cli GET stock::P-100` 이 사람 눈에 읽히게 했다. 캐시가 하나뿐이라 타입을 고정했고, Kotlin 모듈이 든 ObjectMapper 여야 data class 를 되읽는다(처음엔 `GenericJackson2JsonRedisSerializer()` 를 썼다가 리뷰에서 "되읽지 못해 모든 조회가 미스" 가 잡혔다. `CacheConfigTest` 가 이걸 고정한다)
- **TTL 30초** : 차감 뒤 `DEL` 을 놓쳐도(예 : 복원 호출이 inventory 안에서 실패) 30초면 다시 MySQL 을 읽는다. 재고는 자주 바뀌니 길게 두지 않는다
- **지우는 시점은 커밋 뒤** : `StockService.evict` 가 `TransactionSynchronization.afterCommit` 에 등록한다. 커밋 전에 지우면 그 틈에 다른 조회가 옛 값을 다시 넣는다. `RedisCacheManager.transactionAware()` 는 쓰지 않는다 : 커밋 뒤 `DEL` 이 실패하면 예외가 커밋 호출자까지 올라와 차감 성공이 500 이 됐다(E2E 시험에서 잡힘)

### 에이전트가 남기는 스팬 (Service map 재료)

| 언제 | Redis 명령 | 스팬 |
|---|---|---|
| 조회, 캐시에 있음(히트) | `GET stock::P-100` | `GET` CLIENT, `db.system=redis` |
| 조회, 캐시에 없음(미스) | `GET` → (MySQL SELECT) → `SET stock::P-100 ? EX 30` | `GET` · `SELECT` · `SET` 세 개 |
| 차감 · 복원 커밋 뒤 | `DEL stock::P-100` | `DEL` |
| 연결을 맺을 때 (앱 시작 · 재연결) | `HELLO 3` · `CLIENT SETINFO` ×2 | 부모 없는 CLIENT 세 개 (research ⑤). 양이 작아 받아들인다 |

Service map 집계는 `db.system` 이 있으면 DB 로 가르므로 `inventory → redis:6379` 가 MySQL 과 같은 DB 칸에 그려진다. 헬스체크의 `INFO` 는 `management.health.redis.enabled=false` 로 없앴다.

### 고장 나면

| 무엇이 | 어떻게 되나 |
|---|---|
| Redis 가 죽음 | `GET` 이 500ms 뒤 실패 → `LoggingCacheErrorHandler` 가 로그만 남기고 MySQL 을 읽고, `SET` 도 500ms 뒤 실패 → 로그. 조회 200 유지하되 **최대 1초**(실측 1.02초). 차감 · 복원은 커밋 뒤 `DEL` 이 500ms 기다렸다 실패해도 로그만(주문 201, 실측 0.55초). 그동안 DB 커넥션을 쥐고 있어 부하가 크면 풀이 마를 수 있다(더미라 받아들임) |
| Redis 가 비어 있음 (재시작) | 첫 조회가 미스라 MySQL 을 한 번 더 읽는다. 잃을 것이 없다 |
| 값이 낡음 (DEL 놓침) | 최대 30초 동안 조회 값이 틀린다. 차감은 MySQL 이 다시 확인하므로 초과 판매는 없다 |
