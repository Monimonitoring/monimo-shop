# `#32` 재고 서비스 + Redis 캐시

> **이 작업의 기술 설계 문서 한 장.** 문제 → 선택지 → 결정 → 장애가 나면 순서로 읽으면 끝난다.
> 하네스 규칙과 틀은 backend [`docs/seungjo/`](https://github.com/2026-techeer-project-team-b/monimo-backend/blob/HEAD/docs/seungjo/README.md) 가 정본이다. 쇼핑몰 레포에서 한 작업이라 코드와 같은 PR 에 둔다.

- 2026-10-06 ~ 07 / 승조(`@SeungJo-02`) / ADR 없음 (쇼핑몰 안의 결정이라 research ⑧ 원문이 결정 기록) / 이슈 `#32`
- 유저 플로우에서 어디 : **에이전트가 감시하는 쇼핑몰 안**. 주문 → 재고 → 결제 흐름과 재고 → MySQL · Redis 호출이 늘어나, Service map 에 노드 둘(재고 · Redis)과 화살표 넷이 생긴다

## 문제

쇼핑몰 네 서비스 중 재고만 빈 앱이었다. ADR `#17` 이 PetClinic 을 버린 이유가 "결제 · 재고가 없어 외부 API · 캐시 노드가 Service map 에 나오지 않는다" 였는데, 재고와 캐시 쪽이 그대로 남아 있었다.

| 재 본 것 | 값 |
|---|---|
| 작업 전 Service map 간선 (로컬 ClickHouse) | gateway → order, order → payment, order → mysql, payment → pg-stub : 4개. 캐시 노드 없음 |
| 쇼핑몰이 내는 4xx | 404(없는 주문) 뿐. 4xx 비율 알림 규칙을 시험할 재료가 없었다 |

**무엇이 깨지나** : "캐시가 느려져 주문이 느려졌다" 같은 장애를 화면에 보여 줄 수 없고, 4xx 규칙을 시험할 수 없다.

## 선택지

여섯 가지를 정했다. 전체 표는 [`research.md`](research.md) 5 절.

| 항목 | 고른 것 | 버린 것 |
|---|---|---|
| ⓐ 재고 줄이기 | **조건부 UPDATE** (`WHERE qty >= ?`, 0 행이면 409) | `FOR UPDATE`(커넥션 풀 · 핫 행), 낙관적 락(재시도), Redis 차감(Redis 가 죽으면 주문 정지) |
| ⓑ 캐시 | **조회 API 만 cache-aside**, 커밋 뒤 evict, k6 에 조회 손님 | 주문 경로 캐시 확인(낡으면 잘못된 409), write-through, write-behind |
| ⓒ 결제 실패 시 재고 | **선차감 + 복원** (주문 번호로 한 번만) | 예약(무거움), 결제 후 차감(초과 판매 → 환불), 안 되돌림(재고 0 수렴) |
| ⓓ Redis 헬스체크 | **끈다** | 켜 두면 `INFO` orphan span, backend ADR `#50` 재결정 |
| ⓔ Redis 장애 | **timeout 500ms + DB 폴백** | 기본 60초, 에러로 끝내기 |
| ⓕ 장애 주입 | **`inventory-slow` · `inventory-error` · 품절 `P-SOLDOUT`(409)**, Redis 정지는 README 에 손으로 | — |
| ⓖ 대규모 전환 | **더미로 만들고 바꾸는 조건을 적어 둔다** | 처음부터 Redis 원자 차감 · 예약 · 차단기 |

## 결정

- **고른 것** : 위 표. 이름표 `shop-inventory-local-1`, `redis:7.2`, 초기 재고 `P-100` 1,000,000 · `P-SOLDOUT` 0 (`INSERT IGNORE`), order · gateway 에 `inventory=shop-inventory` 매핑, Redis 는 매핑에 넣지 않음
- **버린 것과 이유** : 표의 오른쪽 칸. 공통 이유는 "감시 대상이라 구현은 작게, 장애 신호는 뚜렷하게"
- **되돌리는 조건** (research 7 절) : 핫 상품에 몰려 재고 P95 가 DB 락 대기로 크게 늘면 Redis 원자 차감으로. 결제 단계가 길어져 재고를 잡아 둘 시간이 필요하면 예약으로. 복원 호출 실패로 재고가 어긋나면 메시지 재시도로

결정 원문은 [`research.md`](research.md) 8 절과 [`prompts.md`](prompts.md).

## 장애가 나면

| 무엇이 죽으면 · 틀리면 | 어떻게 되나 | 어떻게 알아채나 |
|---|---|---|
| Redis 정지 | 조회는 MySQL 로 폴백해 200 이되 최대 1초(`GET` · `SET` 각 500ms 대기), 주문은 201 (커밋 뒤 `DEL` 실패는 로그만, 0.55초). 그동안 DB 커넥션을 쥔 채 기다려 부하가 크면 풀이 마를 수 있다 | `inventory → redis` 간선 에러율, 재고 서비스 P95 상승 |
| MySQL 정지 | 재고 차감 불가 → 주문 502 `inventory error`. 조회는 캐시에 있는 동안만 200 | 5xx 비율, `inventory → mysql` 에러 |
| 재고 서비스 정지 | order 의 읽기 타임아웃(5초) 뒤 502 `inventory error`, 결제는 안 부른다 | `order → shop-inventory` 에러, 결제 호출 수 감소 |
| 재고 서비스 타임아웃 (차감은 커밋됐는데 응답만 늦음) | order 는 ERROR 로 보고 502. 재고가 줄어든 채 남을 수 있어 복원을 한 번 불러 본다(멱등, 기록 없으면 404 라 무해) | `stock_deductions` 에 `restored=0` 인데 주문이 FAILED |
| 없는 상품 주문 | inventory 404 → order 는 ERROR 로 보고 502 `inventory error` (더미라 상품 목록이 없어 4xx 로 가르지 않았다) | 502 비율 |
| 복원 호출 실패 (결제 실패 + 재고 서비스도 실패) | 재고가 줄어든 채 남는다. 주문은 이미 502 | `stock_deductions` 에서 `restored=0` 인데 주문이 FAILED 인 줄. 지금은 손으로 본다 (되돌리는 조건 3) |
| 캐시 `DEL` 놓침 | 최대 30초 조회 값이 틀림. 차감은 MySQL 이 확인하므로 초과 판매 없음 | 조회 값과 `stock.qty` 차이 |
| peer 매핑에 redis 를 넣음 | Redis 가 DB 가 아니라 우리 서비스로 그려진다 | Service map 에 `redis:6379` SERVICE 노드 |
| k6 로 같은 상품만 계속 주문 | 주문마다 `DEL` 이 나가 조회가 거의 다 미스(`GET` → `SELECT` → `SET`) | 정상. 히트를 보려면 주문을 멈추고 조회만 |

표별 영향과 Redis 키는 [`tables.md`](tables.md).

## 어떻게 확인했나

- 단위 테스트 41건 (`./gradlew build`) : inventory 17(컨트롤러 8 · 서비스 8 : 409 · 400 · 멱등 복원 · fault, 직렬화 되읽기 1), order 16(컨트롤러 6 · 서비스 4 : 품절이면 결제 안 부름 · 결제 실패면 복원, InventoryClient 6), gateway 5(재고 전달 포함), payment 3
- E2E (compose 7 컨테이너, 리뷰 반영 뒤 다시) : 조회 미스 0.38초 → 히트 0.025초 → 0.007초, 캐시 오류 로그 0건, Redis 값 `{"productId":"P-100","qty":999925}` · 주문 201 뒤 키 삭제 · 품절 409 `SOLD_OUT` · `payment-error` 주문 뒤 qty 변화 0(복원) · 같은 주문 복원 두 번째는 변화 0 · `inventory-error` 502 · `inventory-slow` 1.53초 201 · 게이트웨이로 `POST /api/stock/deduct` 405 · 수량 0 은 400
- 일부러 깨뜨려 본 것 : **Redis 정지** → 조회 200(폴백, 1.02초), 주문 201(0.55초), 헬스체크 UP. Redis 를 다시 켜면 0.013초 히트. 처음엔 주문이 502 였다(prompts.md 「결과」)
- k6 : 초당 5건 · 20초, 결제 실패 10% · 재고 실패 10% · 재고 지연 10% · 품절 10% · 조회 손님 50% → **checks 300/300 (100%)**
- backend 와 같이 : `server_map_1m` 에 `shop-gateway → shop-inventory`(SERVICE 59) · `shop-order → shop-inventory`(SERVICE 125, 에러 31) · `shop-inventory → mysql:3306`(DB 380) · `shop-inventory → redis:6379`(DB 195). 헬스체크 `INFO` 스팬 **0건**, 연결 설정 orphan span 6건(`HELLO` 2 · `CLIENT SETINFO` 4 : 기동 · 재연결 2회분). 조회만 있는 트레이스(`GET` 뒤 `SET` 없음 = 히트) 18건
- 별도 리뷰(code-reviewer) : 첫 판정 REJECT. 캐시 값을 되읽지 못해 모든 조회가 미스였던 것(기본 Jackson 에 Kotlin 모듈 없음)을 잡았다. 처음 E2E 때 "히트 0.04초" 는 예열된 미스를 히트로 잘못 읽은 것이었다. 같이 잡힌 것 : 게이트웨이가 차감 · 복원 POST 까지 열고 있던 것(GET 만으로), 수량 0 이하 무검증, 타임아웃 때 복원 안 부름, 문서의 `transactionAware` 잔재
- CI : PR 에서 build · smoke(품절 10% 포함)

## 결과물

- 새 파일 : `inventory/` (StockDtos · StockRepository · StockService · StockController · CacheConfig · schema.sql · 테스트 3개) · `inventory/Dockerfile` · `order/InventoryClient.kt` · `order/OrderServiceTest.kt` · `order/InventoryClientTest.kt` · 이 폴더
- 수정 : `order/` (OrderService · OrderController · Order.kt `SOLD_OUT` · application.yml · schema.sql · Dockerfile · 테스트) · `gateway/` (`OrderProxy` → `UpstreamProxy`, `/api/stock/**` 전달, application.yml · Dockerfile · 테스트) · `gradle/libs.versions.toml` · `docker-compose.dev.yml` · `k6/order.js` · `k6/README.md` · `.github/workflows/ci.yml` · `README.md` · `AGENTS.md` · `.env.example` · `docs/prompts/README.md`

## 읽는 순서

1. [`prompts.md`](prompts.md)
2. [`research.md`](research.md)
3. [`tables.md`](tables.md)

## 이 이슈에서 배운 것 (세 줄)

- 캐시 오류 처리기(`CacheErrorHandler`)는 애노테이션 경로에만 걸린다. 직접 부른 `evict` 는 따로 잡아야 하고, 그걸 E2E 시험(Redis 정지)이 잡았다
- "빠르게 200" 은 히트의 증거가 아니다. 되읽기에 실패해도 오류 처리기가 미스로 돌려 겉으로는 멀쩡했다. 캐시는 값을 다시 읽는 테스트와 오류 로그 0건으로 확인한다
- 헬스체크가 "진짜 명령" 을 보내는 순간 Collector 필터가 못 거르는 자식 스팬이 생긴다. 끄는 것이 가장 싸다
- "주문만 넣으면 캐시 화살표가 안 보인다" 처럼, Service map 에 무엇이 보일지는 코드가 아니라 **손님의 행동(k6 시나리오)** 이 정한다
