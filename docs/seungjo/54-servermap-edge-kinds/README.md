# `#54` Service map 이 우리 시스템을 틀리게 그린다 (문제 넷)

> **이 작업의 기술 설계 문서 한 장.** 문제 → 선택지 → 결정 → 결과 순서로 읽으면 끝난다.
> 하네스 규칙과 틀은 backend [`docs/seungjo/`](https://github.com/Monimonitoring/monimo-backend/blob/HEAD/docs/seungjo/README.md) 가 정본이다.

- 2026-10-28 / 승조(`@SeungJo-02`) / 멘토 피드백 D / 이슈 `#54` · backend `#259`
- 유저 플로우에서 어디 : **Service map 화면 하나**. 쇼핑몰(감시 대상) · 집계(ClickHouse) · 조회(api-server) · 화면(web) 넷을 다 지난다

## 문제

멘토가 「서비스 **수**보다 **간선 종류**를 늘려라」고 했다. 화면을 직접 띄워 보니 문제가 **넷**이었고, 멘토가 짚은 것은 그중 하나였다.

| | 무엇이 틀렸나 | 증거 (실측 2026-10-28) |
|---|---|---|
| 1 | **필터를 걸면 이웃의 의존이 사라진다** | 필터 없음 간선 14개 · `shop-order` 로 걸면 5개 · `shop-gateway` 로 걸면 DB 노드가 **0개** |
| 2 | **비동기 간선 종류가 없다** | `span_kind` 에 PRODUCER · CONSUMER 가 **이미 있는데** 집계가 `WHERE span_kind = 'CLIENT'` 였다. 스팬 495만건 중 비동기 **0건** |
| 3 | **ClickHouse 호출 수가 2배** | 쿼리 하나가 스팬 둘 : 부모 `SELECT monimo`(DB) + 자식 `POST`(EXTERNAL). `POST` 110,816건 = CH DB 스팬 전부의 합과 **정확히 같다** |
| 4 | **같은 서비스가 두 번 그려진다** | `monimo-detector → api-server:8080` 이 EXTERNAL **31,458건**. 등록된 이름은 `monimo-api-server` |

## 선택지

여섯 묶음을 놓고 골랐다. 전부 [`research.md`](research.md) 5절에 있다.

| 묶음 | 고른 것 | 버린 것과 이유 |
|---|---|---|
| 필터를 어떻게 넓히나 | **서브쿼리로 한 번에** | 두 번 질의(두 질의 사이에 1분 창이 넘어가면 어긋난다) · 화면에서 거르기(서비스가 늘면 받는 양이 는다) |
| 비동기 데이터를 어디서 만드나 | **쇼핑몰 안에 Kafka 토픽 하나** | 서비스 추가(메모리 +75 ~ 126MB · `#51` 실측, 멘토가 「수보다 종류」) · Ingester 트레이스 켜기(backend `#213` 되먹임 결정을 되돌린다) |
| 비동기 간선 모양 | **간선 둘 + Kafka 노드 하나** | 토픽으로 짝지어 하나로 · `trace_id` 로 잇기(둘 다 MV 가 다른 스팬을 못 본다) |
| ClickHouse 2배 세기 | **에이전트에게 겹친 CLIENT 스팬을 접게 한다** | 전용 포트 9000(**드라이버가 거부한다**, 아래) · 계측 모듈 끄기(진짜 외부 호출 간선도 사라진다) |
| 이름을 어디서 맞추나 | **compose 에 이름 짝 한 줄** | 컨테이너 · 서비스 이름 바꾸기(쌓인 기록이 다 바뀐다) · `PeerServiceResolver` 느슨하게(그 코드가 일부러 거부한다) |
| 이번 범위 | **넷 다** | |

## 결정

- **전용 포트(9000) 안은 실제로 붙여 보고 기각했다** : `clickhouse-java 0.10.0` 은 전용 포트에도 HTTP 로 말을 건다. `ClickHouseClient` 를 등록하는 jar 가 `clickhouse-http-client` 하나뿐이다
- **대신 5절에 없던 안으로 갔다** : `OTEL_INSTRUMENTATION_EXPERIMENTAL_SPAN_SUPPRESSION_STRATEGY=span-kind`. 겉 스팬과 같은 종류의 안쪽 스팬을 안 만든다. 겉에 있는 CLIENT 는 남으므로 진짜 외부 호출 간선은 안 사라진다
- **되돌리는 조건** : 안쪽 CLIENT 스팬이 필요해지면 그 줄을 지우고 집계에서 거르는 쪽으로 · 서비스가 수십 개가 되면 필터 깊이를 설정으로 · 토픽이 여럿이 되면 토픽마다 노드로

결정 원문은 [`research.md`](research.md) 6 · 8 절과 [`prompts.md`](prompts.md).

## 결과

| 문제 | 전 | 후 |
|---|---|---|
| 1 필터 | `shop-gateway` 로 걸면 간선 2개 · DB 0개 | 간선 **8개** · DB **3개** (필터 없음은 14개 그대로 : 회귀 없음) |
| 2 비동기 | 간선 종류 셋 · 비동기 스팬 0건 | 간선 종류 **넷** · `shop-order → kafka` 622건 · `kafka → shop-inventory` 655건 |
| 3 CH 2배 | DB 32 · EXTERNAL 32 (같은 1분) | DB 24 · EXTERNAL **0** |
| 4 이름 | `api-server:8080` EXTERNAL 31,458 | `monimo-api-server` **SERVICE** |

## 어떻게 확인했나

- **쇼핑몰 + 백엔드 다섯 + 화면을 다 띄우고 k6 를 돌렸다** : 초당 10건 · 3분 · 요청 2,520건 (에러 주입 8% · 재고 조회 손님 40%)
- **같은 창에서 API 를 필터 걸고 안 걸고 세 번 불러 비교했다** (필터 없음 · `shop-order` · `shop-gateway`)
- **옛 한 겹 조건을 ClickHouse 에 직접 넣어** 전후를 같은 5분 창에서 셌다 (추정이 아니라 같은 데이터로 비교)
- **1분마다 세서 재시작 경계를 봤다** : 00:09 (전) · 00:10 (재시작) · 00:11 (후)
- **드라이버를 실제로 붙여 봤다** : HTTP 포트는 `OK -> 1`, 전용 포트는 ClickHouse 가 거절, `jdbc:ch:tcp://` 는 연결 실패
- **에이전트 jar 에서 설정 이름을 직접 읽었다** : `SpanSuppressionStrategy` 의 값 셋(`NONE` · `SPAN_KIND` · `SEMCONV`)과 `otel.instrumentation.experimental.span-suppression-strategy`
- **테스트** : backend `ServerMapApiTest` 10건(`spans` 에 PRODUCER · CONSUMER 를 직접 넣어 MV 를 거쳐 본다) · shop 주문 · 발행 · 소비 테스트 · web 47건 + lint
- **화면은 눈으로 못 봤다** : 브라우저 확장이 안 붙었다. 데이터는 API 와 ClickHouse 로 다 확인했고 화면은 빌드 · 테스트 · lint 까지다

## 결과물

- **backend** (`#259`) : `compose.yaml` 이름 짝 · `Dockerfile` api-server 단계에 스팬 접기 · `ServerMapRepository` 두 겹 필터 · `db/clickhouse/V202610281140__add_messaging_edges_to_server_map.sql` · `api-spec.md` 41번 · `AGENTS.md` §5 · §6
- **shop** (`#54`) : `shop-kafka` 컨테이너 + 토픽 만들기 · `OrderEventPublisher` · `OrderCompletedListener` · `StockService.refreshCache` · 이 폴더 문서 넷
- **web** : `CalleeKind` 에 `MESSAGING` · `MessagingNode`(육각형) · 간선 선 모양 표 · 범례 · 가짜 응답 · 계약 테스트

## 읽는 순서

1. [`prompts.md`](prompts.md)
2. [`research.md`](research.md)

## 이 이슈에서 배운 것 (세 줄)

- **화면을 띄워 보면 문제가 하나가 아니다** : 멘토가 짚은 하나를 보러 갔다가 넷을 찾았고, 셋은 Service map 이 **틀린 숫자를 보여 주고 있던** 것이었다
- **같은 데이터로 비교해야 전후를 말할 수 있다** : 옛 조건을 ClickHouse 에 직접 넣어 같은 5분 창에서 세고 나서야 「2 → 8」을 쓸 수 있었다
- **문서에 적은 선택지가 다일 거라고 믿지 않는다** : 고른 안(전용 포트)이 드라이버에서 막혀 5절에 없던 안을 찾아야 했다
