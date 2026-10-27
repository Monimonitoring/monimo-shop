# 리서치 : 서비스맵 간선 종류를 늘린다 (비동기 간선이 아예 안 그려진다)

- 날짜 2026-10-28 / 관련 이슈 [`#54`](https://github.com/Monimonitoring/monimo-shop/issues/54) (멘토 피드백 D · 「서비스 **수**보다 **간선 종류**를 늘려라」)
- 쓴 도구 : backend 레포 직접 읽기(`db/clickhouse/V202609221907__create_materialized_views.sql` · `spans` · `server_map_1m` 표 정의 · `ingester/.../PeerServiceResolver.kt` · `SpanTranslator.kt` · `api-server/.../ServerMapRepository.kt` · `otel/agent.properties` · `Dockerfile` · `AGENTS.md` §5 `#213`) · web 레포 `features/server-map/` 넷 · **ClickHouse 직접 조회**(`monimo.spans` 495만 건의 `span_kind` 분포 · 서비스별 분포) · shop `#32`(재고 Redis) · `#51`(오버헤드) 재독

- **읽는 순서가 곧 쓰는 순서다**
  - 1 → 2 를 쓰고 멈춰서 승조가 3 을 묻는다. 답하고 4 를 쓴다
  - 5 를 쓰고 멈춰서 승조가 6 을 묻는다. 답하고 7 을 쓴다
  - 8 은 승조가 쓴다
- **3 · 6 · 8 은 사람 차례다**
  - 비어 있으면 아직 거기까지 온 것이다
  - 대신 채우지 않는다

---

## 1. 이번 작업은 무엇을 하는 일인가

- **지금 무엇이 잘못됐나** : 멘토가 「간선 **종류**를 늘려라」 하며 셋을 들었는데(Kafka 비동기 · Redis · 외부 API), **둘은 이미 그려지고 하나는 아예 안 그려진다**. 안 그려지는 것이 **비동기(메시징)** 다. 그리고 안 그려지는 이유가 쇼핑몰이 아니라 **백엔드 집계와 화면에 있다** : 집계가 `CLIENT` 스팬만 읽고, 간선 종류 칸이 셋뿐이고, 화면 노드 모양도 셋뿐이다
- **그래서 무엇을 만드나** : 서비스맵에 **비동기 간선 한 종류**를 더한다. 그러려면 네 자리가 같이 움직인다(쇼핑몰 · 저장 · 조회 · 화면). 이 문서는 **어디를 어떻게 바꿀지와, 비동기 간선을 어떤 모양으로 그릴지**를 정하는 것이 목표다
- **안 하면 어떻게 되나** : 서비스맵이 **동기 호출만 그리는 그림**으로 남는다. 면접관이 「비동기 구간은 어떻게 보나」 물으면 답이 없다. 그리고 우리 백엔드 자체가 Kafka 로 돌아가는데(Collector → Kafka → Ingester) 그 구간이 우리 화면에 안 보인다

| | |
|---|---|
| 건드리는 모듈 · 파일 | **쇼핑몰** : 비동기 경로를 만들 자리(어디에 둘지는 5절에서 정한다) · **backend** : `db/clickhouse/` 마이그레이션 한 장(`server_map_1m.callee_kind` Enum + `mv_server_map_1m` `ALTER ... MODIFY QUERY`) · **web** : `features/server-map/{nodes.tsx,model.ts,ServerMapGraph.tsx}` · 이 폴더 문서 넷 |
| 건드리지 않는 것 | **지금 그려지는 간선 셋을 깨지 않는다**(SERVICE · DB · EXTERNAL) · `spans` 표의 `span_kind` Enum(이미 PRODUCER · CONSUMER 가 있다) · `PeerServiceResolver` 의 채우기 규칙(이미 PRODUCER 를 호출자로 다룬다) · Collector · Ingester 의 `OTEL_TRACES_EXPORTER=none`(되먹임 때문에 일부러 꺼 둔 것, 아래 2.4) |
| 이 이슈 범위 밖 (후속) | 서비스 **수** 늘리기(멘토가 그게 아니라고 했다) · 트레이스 상세 화면의 비동기 표시 · 비동기 구간의 경보 규칙 · `#51` 후속 셋(계측 범위 10회차 · 빠진 메트릭 이름 · 로그 `trace_id`) |

**조사 들어가기 전에 내가 알던 것 (세 줄)**

- 서비스맵은 **`mv_server_map_1m` 이 1분마다 접은 것**을 조회가 읽는다. 간선 종류는 `callee_kind` 한 칸이다
- `#32` 에서 **Redis 를 peer 매핑에 일부러 안 넣었다** : 넣으면 Redis 가 DB 가 아니라 우리 서비스로 잘못 그려진다. pg-stub(외부 결제사)도 같은 이유로 안 넣었다
- `#51` 에서 **서비스를 하나 늘리면 에이전트 몫으로 메모리 75 ~ 126MB** 가 붙는다. 멘토가 「노드 크기와 같이 본다」고 한 숫자다

**알고 싶었던 것** (다섯)

- ① **지금 정말 몇 종류가 그려지나** : 멘토가 든 셋 중 무엇이 되고 무엇이 안 되나. 「안 된다」가 **코드 때문인지 데이터가 없어서인지** 갈라야 한다
- ② **비동기 스팬이 집계까지 오나** : 에이전트가 PRODUCER · CONSUMER 스팬을 만들어도 중간에서 버려지면 쇼핑몰만 고쳐서는 화면에 안 나온다. **어디서 끊기나**
- ③ **우리 백엔드에 이미 Kafka 가 있는데 왜 안 보이나** : Collector → Kafka → Ingester 가 실제 비동기 경로다. 데이터가 없는 것인지 일부러 끈 것인지
- ④ **비동기 간선은 어떤 모양이어야 하나** : HTTP 는 한 스팬에 호출자와 피호출자가 다 있다. Kafka 는 보낼 때 **누가 받을지 모른다**. 그러면 간선을 하나로 그릴 수 있나
- ⑤ **서비스를 새로 만들어야 하나** : 비동기 간선을 만들려고 서비스를 늘리면 메모리가 서비스당 75 ~ 126MB 붙는다. **기존 서비스 사이에 토픽만 두는 방법**이 되나

## 2. 작업하기 전에 알아야 하는 것

### 2.1 서비스맵 한 간선이 어디서 어떻게 만들어지나

```
[에이전트]  요청마다 스팬을 만든다
              span_kind = SERVER · CLIENT · INTERNAL · PRODUCER · CONSUMER
              peer.service 는 안 넣는다 (2.x) · server.address 만 넣는다
                 ↓ OTLP
[Collector]  받아서 Kafka raw 로 넘긴다
                 ↓
[Ingester]   peer_service 가 비어 있으면 주소에서 채운다 (PeerServiceResolver)
                 ↓ INSERT
[spans]      span_kind Enum8('INTERNAL','SERVER','CLIENT','PRODUCER','CONSUMER')
                 ↓ mv_server_map_1m  ← 여기서 CLIENT 만 통과한다
[server_map_1m]  caller_service · callee_service · callee_kind · cnt · err_cnt
                 ↓ ServerMapRepository
[화면]       nodeTypes = { SERVICE, DB, EXTERNAL }
```

- **간선 하나는 「호출하는 쪽 스팬 한 줄」에서 나온다** : 호출자는 그 스팬의 `service_name`, 피호출자는 `peer_service` 나 `peer_address`
- 즉 **피호출자가 에이전트를 안 달고 있어도 간선은 그려진다** (MySQL · Redis · pg-stub 이 그렇다)

### 2.2 지금 그려지는 간선 종류는 셋이고, 어떻게 갈리나

`mv_server_map_1m` 의 분류 식 그대로다.

| 종류 | 조건 | 쇼핑몰에서 무엇 |
|---|---|---|
| `SERVICE` | `peer_service` 가 비어 있지 않다 | gateway → order · gateway → inventory · order → payment · order → inventory |
| `DB` | `peer_service` 는 비었고 `attributes['db.system']` 이 있다 | order → MySQL · inventory → MySQL · **inventory → Redis** |
| `EXTERNAL` | 둘 다 아니다 | **payment → pg-stub** (외부 결제사) |

- **`peer_service` 를 누가 채우나가 종류를 정한다**
  - 쇼핑몰이 `OTEL_INSTRUMENTATION_COMMON_PEER_SERVICE_MAPPING` 으로 넣는 것 : `payment=shop-payment` · `inventory=shop-inventory` · `order=shop-order`
  - Ingester 가 주소에서 채우는 것 : 주소 첫 라벨이 **감시 중인 서비스 이름과 정확히 같을 때만**
- **Redis 와 pg-stub 을 일부러 안 채웠다** (`#32`) : 채우면 우리 서비스 노드로 그려져 그림이 거짓이 된다. 즉 **지금 종류 셋은 우연이 아니라 설계다**
- **멘토가 든 셋 중 둘은 이미 됐다** : Redis(DB) · 외부 API(EXTERNAL). 남은 하나가 **비동기**다

### 2.3 비동기 간선이 안 그려지는 이유 셋 (전부 확인했다)

| 끊기는 자리 | 지금 | 근거 |
|---|---|---|
| ① **집계** | `WHERE span_kind = 'CLIENT'` | `V202609221907__create_materialized_views.sql` |
| ② **칸** | `callee_kind Enum8('SERVICE'=0,'DB'=1,'EXTERNAL'=2)` | `server_map_1m` 표 정의 |
| ③ **화면** | `nodeTypes = { SERVICE, DB, EXTERNAL }` · `MapNode` 타입도 셋 | web `features/server-map/{ServerMapGraph.tsx,nodes.tsx}` |

- **①이 가장 앞이다** : 쇼핑몰에 Kafka 를 붙여 PRODUCER 스팬이 생겨도 **집계가 통째로 버린다**
- **②는 마이그레이션이 필요하다** : ClickHouse Enum 에 값을 더하는 `ALTER TABLE ... MODIFY COLUMN` 과 MV 의 `ALTER ... MODIFY QUERY` 가 짝이다. `db/clickhouse/README.md` 의 절차(MV 는 `MODIFY QUERY` 만 · `ADD COLUMN` 이 MV 수정보다 먼저 · 바꾼 뒤 각 칸에 값이 들어오는지 확인)를 따라야 한다
- **③은 모양 하나 추가**라 작다. 조회(`ServerMapRepository`)는 `toString(callee_kind)` 로 그대로 흘리므로 **고칠 것이 사실상 없다** (API 명세의 enum 목록만)

**실측으로 ①을 확인했다** : `monimo.spans` 495만 건의 `span_kind` 분포

| `span_kind` | 건수 |
|---|---|
| `INTERNAL` | 2,816,215 |
| `CLIENT` | 2,003,970 |
| `SERVER` | 133,232 |
| **`PRODUCER`** | **0** |
| **`CONSUMER`** | **0** |

- 즉 **지금은 데이터 자체가 없다** : ①을 고쳐도 당장 보일 간선이 없다. 데이터를 만드는 일(쇼핑몰 또는 백엔드)이 같이 필요하다

### 2.4 우리 백엔드에 이미 Kafka 가 있는데 왜 0건인가 (일부러 껐다)

- 실제 비동기 경로가 **이미 있다** : **Collector → Kafka(`raw`) → Ingester**
- 그런데 그 둘만 트레이스를 안 보낸다

```
Dockerfile
  collector 단계 : ENV OTEL_SERVICE_NAME=monimo-collector
                        OTEL_TRACES_EXPORTER=none
  ingester  단계 : ENV OTEL_SERVICE_NAME=monimo-ingester
                        OTEL_TRACES_EXPORTER=none
  api-server · detector · notifier : 트레이스 켬
```

- 이유가 `#213` 에 적혀 있다 : **「자기 스팬이 자기 입력으로 돌아오는 되먹임」**
  - Collector 가 OTLP 를 받는 일에 스팬이 생기면 그 스팬이 다시 Collector 로 들어온다
  - Ingester 가 Kafka 에서 읽는 일에 스팬이 생기면 그 스팬이 또 Kafka 로 간다
- **실측이 이 말과 맞는다** : 최근 7일 스팬에 `monimo-api-server` · `monimo-detector` · `monimo-notifier` 만 있고 **Collector · Ingester 는 0건**이다
- **그래서 가장 가까운 Kafka 간선이 의도적으로 가려져 있다** : 이것을 켜는 것은 되먹임을 다시 들이는 일이라 **선택지로 올리되 조심해야 한다**(5절)

### 2.5 Kafka 간선은 HTTP 간선과 모양이 다르다 (이번 설계의 핵심)

HTTP 는 **한 스팬이 간선 하나를 통째로 안다**.

```
order 의 CLIENT 스팬 하나
  service_name  = shop-order        ← 호출자
  peer_service  = shop-payment      ← 피호출자
  => 간선 : shop-order -> shop-payment
```

Kafka 는 **보낼 때 누가 받을지 모른다**.

```
order 의 PRODUCER 스팬
  service_name            = shop-order
  server.address          = kafka:9092        ← 브로커다. 받는 서비스가 아니다
  messaging.destination   = orders.created    ← 토픽 이름

(한참 뒤, 다른 트레이스 조각)

notifier 의 CONSUMER 스팬
  service_name            = shop-notifier
  messaging.destination   = orders.created    ← 같은 토픽
```

- **`PeerServiceResolver` 는 이미 PRODUCER 를 호출자로 다룬다** (`CALLER_KINDS = setOf("CLIENT","PRODUCER")`)
- 그런데 주소가 **브로커**(`kafka:9092`)라 감시 대상 이름과 안 맞아 `peer_service` 가 **빈 채로 남는다**
- 지금 분류 식에 그대로 넣으면 `db.system` 도 없으니 **`EXTERNAL` 로 그려진다** : 「Kafka 라는 외부 시스템」 노드 하나
- 그러면 **받는 쪽이 그림에 안 이어진다** : `order → kafka` 까지만 보이고 `kafka → notifier` 가 없다

**그려지는 모양 후보 셋** (고르는 것은 5절, 묻는 것은 3절)

| 후보 | 간선 | 필요한 것 | 걸리는 것 |
|---|---|---|---|
| ① 두 간선으로 쪼갠다 | `order → kafka` + `kafka → notifier` | CONSUMER 스팬도 집계에 넣고 **받는 쪽을 호출자로 뒤집어** 읽어야 한다 | Kafka 노드가 그림에 하나 생긴다. 토픽이 여럿이면 노드가 하나로 뭉친다 |
| ② 토픽으로 잇는다 | `order → notifier` (토픽은 간선 이름표) | PRODUCER 와 CONSUMER 를 **토픽으로 조인**해야 한다. 1분 집계 안에서 짝이 안 맞을 수 있다 | MV 하나로는 안 된다. 집계 구조를 바꿔야 한다 |
| ③ 트레이스 문맥으로 잇는다 | `order → notifier` | Kafka 헤더로 넘어온 `trace_id` 로 부모 · 자식을 잇는다 | 서비스맵은 **1분 단위 접기**이고 트레이스 단위가 아니다. 접는 단위가 달라진다 |

### 2.6 서비스를 새로 만들어야 하나 (메모리 때문에 먼저 따진다)

- `#51` 실측 : 에이전트를 붙인 서비스는 **메모리 75 ~ 126MB** 를 더 쓴다. CPU 는 주문 1건당 **+3.37ms(+20%)**
- 쇼핑몰은 지금 네 서비스다. 다섯째를 만들면 **메모리가 한 몫 더 붙고** EKS 노드 크기 계산이 올라간다
- **서비스를 안 늘리고 비동기를 만드는 길이 있다** : 기존 서비스 둘 사이에 토픽을 둔다
  - 예 : `order` 가 주문 성공을 토픽에 넣고 `inventory` 가 받아서 뒤처리 (지금은 동기 HTTP 로 차감한다)
  - 예 : `order` 가 넣고 `payment` 가 받아서 정산
- 즉 **「간선 종류를 늘린다」와 「서비스를 늘린다」는 갈라서 결정할 수 있다** (멘토가 가른 바로 그 지점이다)

### 2.7 지금 값 (레포에서 읽은 것)

| 무엇 | 지금 | 어디 |
|---|---|---|
| 집계가 읽는 `span_kind` | `CLIENT` 하나 | backend `db/clickhouse/V202609221907__create_materialized_views.sql` |
| 간선 종류 칸 | `Enum8('SERVICE'=0,'DB'=1,'EXTERNAL'=2)` | backend `server_map_1m` |
| 스팬 종류 칸 | `Enum8('INTERNAL'=0,'SERVER'=1,'CLIENT'=2,'PRODUCER'=3,'CONSUMER'=4)` | backend `spans` |
| 호출자로 보는 종류 | `setOf("CLIENT","PRODUCER")` | backend `PeerServiceResolver.kt` |
| 화면 노드 모양 | `{ SERVICE, DB, EXTERNAL }` | web `features/server-map/ServerMapGraph.tsx` |
| 쇼핑몰 Kafka | **없다** | `docker-compose.dev.yml` 에 Kafka 컨테이너가 없다 |
| 백엔드 Collector · Ingester 트레이스 | `OTEL_TRACES_EXPORTER=none` | backend `Dockerfile` 54 · 62행 |
| 서비스 하나당 에이전트 메모리 | +75 ~ 126MB | shop `#51` `results.md` |

### 2.8 선례 : 간선 종류를 건드린 일이 두 번 있었다

- **`#32`(재고 Redis)** : Redis 를 peer 매핑에 **안 넣기로** 정했다. 넣으면 DB 가 아니라 우리 서비스로 그려진다. **잘못 그려지는 것보다 안 그려지는 것이 낫다**는 판단이었다
- **backend `#92`(헬스체크 거르기)** : 헬스체크 SERVER 스팬을 버린다. 그런데 그 요청 안에 CLIENT 스팬이 생기면 **`mv_server_map_1m`(CLIENT 전용)에 들어가 헬스체크 발 DB · EXTERNAL 간선**을 만든다 (backend `AGENTS.md` §6 에 열린 행으로 있다)
  - **이번에 PRODUCER · CONSUMER 를 집계에 넣으면 같은 모양의 위험이 생긴다** : 걸러야 할 스팬이 늘어난다

---

## 3. 1차 질문 (사람 차례)

<!-- 비어 있다. 승조가 묻는다 -->

## 4. 1차 정리

<!-- 3 에 답한 뒤 쓴다 -->

## 5. 선택지와 설명

<!-- 4 뒤에 쓴다 -->

## 6. 2차 질문 (사람 차례)

<!-- 비어 있다. 승조가 묻는다 -->

## 7. 2차 정리

<!-- 6 에 답한 뒤 쓴다 -->

## 8. 구현 프롬프트 (사람 차례)

<!-- 비어 있다. 승조가 쓴다 -->

## 9. 프롬프트를 가지고 실제 구현

<!-- 8 뒤에 쓴다 -->
