# monimo-shop

감시 대상 쇼핑몰 4종(게이트웨이 · 주문 · 결제 · 재고)과 OTel Java Agent 설정, 스레드 덤프 Extension

- 기술: Kotlin 2.2 · Spring Boot 3.5 · Java 17 · Gradle 8.14 (멀티모듈 5개: gateway · order · payment · inventory · agent-extension) · OTel Java Agent
- 상태: 네 서비스(gateway · order · payment · inventory + Redis 캐시) · 에이전트 부착 · mTLS · 스레드 덤프 Extension · 오버헤드 측정까지 완료
- 용어: 문서 · 주석은 현업 이름(Collector · Ingester · Watchdog 등)을 쓴다. 대응표는 [backend `docs/design/glossary.md`](https://github.com/Monimonitoring/monimo-backend/blob/develop/docs/design/glossary.md) (backend `#240`)

## 폴더 구성

| 폴더 | 하는 일 |
|---|---|
| `gateway/` | 게이트웨이 (입구, 1b). `/api/orders` 는 주문으로, `/api/stock` 은 재고로 넘긴다 |
| `order/` | 주문 (1a) |
| `payment/` | 결제 (1a) |
| `inventory/` | 재고 (1b). MySQL `stock` 표 + Redis 조회 캐시 |
| `agent-extension/` | 스레드 덤프 명령 수신 Extension (Java, 우리가 만드는 유일한 에이전트 코드). [`agent-extension/README.md`](agent-extension/README.md) |
| `otel/` | OTel Java Agent 설정 · 버전 고정 — `agent.properties`(전송 gRPC · Collector `collector:4317` · 샘플러 always_on) · `AGENT_VERSION` |
| `k6/` | 부하 · 에러 주입 시나리오 |
| `pg-stub/` | 더미 외부 결제사 (WireMock 응답 정의, 에이전트 없음) |
| `perf/overhead/` | 에이전트 오버헤드 측정 (붙임 · 뗌을 같은 부하로 비교). [`perf/overhead/README.md`](perf/overhead/README.md) |
| `Dockerfile` (루트) | 네 서비스 이미지를 파일 하나로 만든다. Gradle 은 한 번만 돌고 서비스마다 `--target gateway · order · payment · inventory` 로 고른다 (`#42`). 한 서비스만 : `docker build --target order -t monimo/shop-order:dev .` |

## 로컬 실행

필요한 것: Docker (에이전트까지 붙여 띄울 때), JDK 17 (코드만 빌드 · 테스트할 때. 없으면 Gradle 이 자동으로 내려받는다)

### 한 번에 켜기 — compose (에이전트 부착)

MySQL · Redis · 더미 외부 결제사(pg-stub) · 결제 · 재고 · 주문 · 게이트웨이 일곱 컨테이너가 순서대로 뜬다(결제사 뒤 결제, MySQL · Redis 뒤 재고, MySQL · 재고 · 결제 뒤 주문, 주문 뒤 게이트웨이). 손님은 게이트웨이(8090)로 들어온다. 이미지에 OTel Java Agent(버전 `otel/AGENT_VERSION`)가 들어 있어 `collector:4317` 로 트레이스 · 메트릭 · 로그를 보낸다. 앱 코드에는 계측이 없다(ADR #33).

```bash
docker network create monimo-dev                                 # 처음 한 번만 (backend 와 같이 쓰는 공용 네트워크)
docker compose -f docker-compose.dev.yml up -d --build --wait    # 켜기 (일곱 다 healthy 가 될 때까지 기다림)
docker compose -f docker-compose.dev.yml logs order | grep -m1 "opentelemetry-javaagent - version"   # 에이전트가 붙었는지
docker compose -f docker-compose.dev.yml down                    # 끄기 (주문 · 재고 데이터는 볼륨에 남음, 재고를 처음(1,000,000)으로 되돌리려면 down -v)

# 확인
curl localhost:8090/api/stock/P-100                              # 재고 조회 (처음은 MySQL, 다음부터 30초간 Redis)
curl -X POST localhost:8090/api/orders -H 'Content-Type: application/json' \
  -d '{"productId":"P-100","quantity":2,"amount":15000}'          # 201, 재고 2 감소
curl -X POST localhost:8090/api/orders -H 'Content-Type: application/json' \
  -d '{"productId":"P-SOLDOUT","quantity":1,"amount":1000}'       # 409 sold out (재고 0)
```

Redis 가 죽으면 어떻게 되나 보려면 `docker compose -f docker-compose.dev.yml stop redis` 뒤에 재고를 조회한다. MySQL 로 대신 읽어 200 이 오되 최대 1초 걸리고(`spring.data.redis.timeout=500ms` 를 `GET` · `SET` 이 한 번씩 기다린다, `LoggingCacheErrorHandler`), 주문은 그대로 201 이다. 트레이스에는 실패한 Redis 스팬과 지연이 남는다. `start redis` 로 되돌린다. 재고 서비스의 헬스체크는 Redis 를 보지 않아(`management.health.redis.enabled=false`) 그동안에도 UP 이다.

Service map 에서 서비스끼리 잇는 이름은 compose 의 `OTEL_INSTRUMENTATION_COMMON_PEER_SERVICE_MAPPING`(호스트 이름 → 서비스 이름, 지금 order 에 `payment=shop-payment,inventory=shop-inventory`, gateway 에 `order=shop-order,inventory=shop-inventory`)이 정한다. 비어 있으면 상대가 외부 시스템으로 잡힌다. 새 서비스가 생기면 여기에 짝을 더하고, 외부 결제사 `pg-stub` 은 넣지 않는다. MySQL · Redis 도 넣지 않는다 : 에이전트가 `db.system` 을 붙여 DB 로 잡히는데, 매핑에 넣으면 우리 서비스로 잘못 그려진다.

에이전트 이름표(`service.instance.id`)는 compose 의 `OTEL_RESOURCE_ATTRIBUTES` 가 정한다(`shop-order-local-1` 처럼). 안 주면 에이전트가 뜰 때마다 랜덤 UUID 를 만들어, 재시작할 때마다 백엔드 화면에 새 에이전트가 붙은 것처럼 보인다(`#30`). 백엔드는 이 값을 `agent_id` 로 쓴다. 같은 키라 `agent.properties` 의 `deployment.environment.name=local` 을 통째로 덮으므로 함께 적는다.

쿠버네티스에서는 Downward API 로 파드 이름을 넣는다. `POD_NAME` 을 **먼저** 정의해야 아래 줄의 `$(POD_NAME)` 이 바뀐다(순서가 틀리면 글자 그대로 들어가고 경고도 없다). 컨테이너만 재시작하면 이름이 그대로고, 배포로 파드가 새로 뜨면 새 이름이 된다.

```yaml
env:
  - name: POD_NAME
    valueFrom: { fieldRef: { fieldPath: metadata.name } }
  - name: POD_NAMESPACE
    valueFrom: { fieldRef: { fieldPath: metadata.namespace } }
  - name: OTEL_RESOURCE_ATTRIBUTES   # 반드시 위 두 개보다 아래
    value: "service.instance.id=$(POD_NAME),k8s.pod.name=$(POD_NAME),k8s.namespace.name=$(POD_NAMESPACE),deployment.environment.name=<환경>"
```

이름은 파드 이름 그대로 쓴다. 같은 백엔드로 두 네임스페이스(staging · prod)의 쇼핑몰이 같이 들어오게 되면 `$(POD_NAMESPACE).$(POD_NAME)` 으로 바꾼다. 결정 과정은 [`docs/seungjo/30-service-instance-id/`](docs/seungjo/30-service-instance-id/README.md).

스레드 덤프 Extension(`agent-extension.jar`)도 네 이미지에 들어 있다. Collector(`collector:8081`)에 명령을 받으러 가는데, Collector 가 꺼져 있으면 1 → 60초 간격으로 다시 물을 뿐 쇼핑몰은 정상으로 뜬다. 토큰은 `OTEL_MONIMO_AGENT_TOKEN`(기본 `local-agent-token`, Collector `MONIMO_AGENT_TOKEN` 과 같아야 함). 덤프를 손으로 떠 보는 법은 [`agent-extension/README.md`](agent-extension/README.md).

데이터를 Collector 까지 보내려면 monimo-backend 에서 `docker compose --profile collector up -d --wait` 로 Collector 를 같이 켠다. Collector 가 꺼져 있어도 쇼핑몰은 정상으로 뜬다(에이전트는 전송 실패를 로그로만 남긴다).

Collector 가 에이전트 신분증을 요구하면(backend `.env` 의 `COLLECTOR_TLS_CLIENT_AUTH=optional|require`, backend `#159` · ADR `#64`) 쇼핑몰 `.env` 에 `MONIMO_OTLP_CERTS_DIR=../monimo-backend/certs` 한 줄을 열고 다시 띄운다. 인증서는 backend compose 가 켤 때 만들고, 네 서비스는 그 폴더의 `env/shop-<서비스>.env` 로 `https` 주소와 각자 이름의 신분증(`clients/shop-<서비스>.crt` · `.key`)을 받는다. Collector 는 도장만 보고 이름은 검사하지 않는다. 자바 코드는 0줄이다. 잘 붙었는지는 Collector 관리 포트의 `/actuator/metrics/monimo.collector.otlp.connections?tag=auth:mtls` 가 4 인지로 본다. 신호가 갑자기 끊기면 **신분증 기간(5년)이 지났는지**를 먼저 본다 : 갱신은 backend `certs/` 를 지우고 다시 만든 뒤 Collector 와 쇼핑몰을 재시작하는 것이다.

### 코드만 빠르게 — Gradle (에이전트 없음)

```bash
./gradlew build                 # 5개 모듈 컴파일 + 테스트(41건) + 서비스 4개 bootJar

docker compose -f docker-compose.dev.yml up -d --wait mysql redis pg-stub   # MySQL · Redis · 결제사 스텁만 컨테이너로
./gradlew :payment:bootRun      # 8092 (터미널 하나)
./gradlew :inventory:bootRun    # 8093 (터미널 둘). 뜰 때 schema.sql 로 stock 표와 초기 재고를 넣는다
./gradlew :order:bootRun        # 8091 (터미널 셋). 뜰 때 schema.sql 로 orders 표를 만들고, MySQL 이 없으면 뜨지 않는다
```

compose 로 결제 · 주문을 켜 둔 상태에서 `bootRun` 을 하면 포트가 겹친다. 둘 중 하나만 쓴다.

## API

감시 대상이라 제품 기능은 없고 호출 골격만 있다. 손님은 게이트웨이로 들어오고, 주문 한 건이 `gateway → order` · `order → MySQL` · `order → inventory` · `inventory → MySQL` · `order → payment` · `payment → 외부 결제사(pg-stub)` 여섯 홉을 만든다. 재고 조회 한 건은 `gateway → inventory` · `inventory → Redis`(캐시에 없으면 `→ MySQL` 뒤 `→ Redis`)다.

Watchdog 카나리와 k6 가 이 모양을 그대로 쓴다.

| 메서드 · 경로 | 서비스 | 응답 |
|---|---|---|
| `POST /api/orders` · `GET /api/orders/{id}` · `GET /api/stock/{productId}` | gateway (입구, 8090) | 주문 · 재고 서비스의 응답을 상태 코드 · 본문 그대로 돌려준다. 재고는 GET 만 연다(차감 · 복원 POST 는 405). 뒤 서비스가 안 닿으면 502 `{reason: upstream unavailable}` |
| `POST /api/orders` `{productId, quantity, amount}` | order | 201 `{orderId, status: PAID, paymentId}` · 재고 부족 409 `{status: SOLD_OUT, reason: sold out}` · 결제 · 재고 서비스 실패 502 `{status: FAILED, reason}` (결제 실패면 재고를 되돌린다) |
| `GET /api/orders/{id}` | order | 200 주문 한 건 · 없으면 404 `{reason}` |
| `GET /api/stock/{productId}` | inventory | 200 `{productId, qty}` · 없으면 404. Redis 에 30초 캐시(cache-aside), Redis 가 죽으면 MySQL 로 |
| `POST /api/stock/deduct` `{orderId, productId, quantity}` | inventory (order 만 부름) | 200 `{orderId, productId, remaining}` · 모자라면 409 `{reason: sold out}` · 자체 주입 시 500. 같은 orderId 는 두 번 차감하지 않는다 |
| `POST /api/stock/restore` `{orderId}` | inventory (order 만 부름) | 200 `{remaining}` · 차감 기록 없으면 404. 같은 orderId 는 한 번만 더한다 |
| `POST /api/payments` `{orderId, amount}` | payment (order 만 부름) | 200 `{paymentId, status: APPROVED}` · 자체 주입 시 500 `{reason}` · 결제사 실패 시 502 `{reason: pg failure}` |
| `POST /v1/approvals` `{orderId, amount}` | pg-stub (payment 만 부름) | 200 `{approvalId, status: APPROVED}` · 503 |

에러 · 지연 주입: 주문 요청에 헤더 `X-Shop-Fault` 를 붙이면 order 가 inventory · payment 로 넘긴다. 품절은 헤더가 아니라 재고 0 인 상품 `P-SOLDOUT` 을 주문해 만든다. 이름과 값은 k6 와의 약속이라 바꾸면 k6 도 같이 바꾼다.

| `X-Shop-Fault` | 동작 | 쓰임 |
|---|---|---|
| `payment-error` | payment 500 → order 502 | 5xx 비율 규칙 시험 |
| `payment-slow` | payment 가 2초 잠든 뒤 정상 응답 | p95 지연 규칙 시험 |
| `pg-error` | 결제사 503 → payment 502 → order 502 | 외부 의존 장애 (우리 문제와 구분) |
| `pg-slow` | 결제사가 1.5초 뒤 응답 | 외부 의존 지연 (콜트리에서 결제사 구간이 길어짐) |
| `inventory-error` | inventory 500 → order 502 (결제는 안 부른다) | 재고 서비스 장애 (결제 장애와 구분) |
| `inventory-slow` | inventory 가 1.5초 잠든 뒤 정상 응답 | 재고 구간 지연 |
| (상품 `P-SOLDOUT`) | inventory 409 → order 409 `SOLD_OUT` | 4xx 비율 규칙 시험 (헤더 없음) |

## 부하 · 에러 주입 (k6)

쇼핑몰을 compose 로 켠 뒤 레포 루트에서 실행한다. 자세한 시나리오와 환경변수는 [`k6/README.md`](k6/README.md).

```bash
# 초당 5건 · 20초, 그중 30% 결제 실패(502) · 10% 결제 2초 지연 · 10% 품절(409). 절반은 주문 전에 재고를 조회한다
docker run --rm --network monimo-dev -v "$PWD/k6:/scripts" -e BASE_URL=http://gateway:8090 \
  -e RATE=5 -e DURATION=20s -e ERROR_RATE=0.3 -e SLOW_RATE=0.1 -e SOLDOUT_RATE=0.1 \
  grafana/k6:2.3.0 run /scripts/order.js
```

## 에이전트 오버헤드 측정

에이전트를 붙인 쪽과 뗀 쪽을 같은 부하(k6 고정 요청률)로 돌려 CPU · 메모리 차이를 잰다. 쇼핑몰을 내려 둔 채 레포 루트에서 실행한다(스크립트가 직접 띄우고 내린다). 방법은 [`perf/overhead/README.md`](perf/overhead/README.md), 결과는 [`docs/seungjo/36-agent-overhead/results.md`](docs/seungjo/36-agent-overhead/results.md).

```bash
perf/overhead/run.sh on 20 && perf/overhead/run.sh off 20   # 한 쌍 (각 약 3분 30초)
python3 perf/overhead/summarize.py                           # 서비스별 중앙값 표
```

## 환경변수

실제 값은 레포에 올리지 않는다. `.env.example` 에 이름만 적는다.

로컬 실행용 (`.env.example` 참고, 비워 두면 기본값):

| 이름 | 기본값 | 설명 |
|---|---|---|
| `MYSQL_PORT` · `REDIS_PORT` | 13306 · 16379 | MySQL · Redis 호스트 포트 |
| `GATEWAY_PORT` · `ORDER_PORT` · `PAYMENT_PORT` · `INVENTORY_PORT` | 8090 · 8091 · 8092 · 8093 | 쇼핑몰 서비스 호스트 포트 |
| `MYSQL_USER` · `MYSQL_PASSWORD` | shop · shop | 로컬 전용 계정 |
| `MYSQL_HOST` · `MYSQL_DATABASE` | localhost · shop | 주문 · 재고 서비스가 붙는 MySQL. `bootRun` 기준 기본값이고, compose 에서는 이미지 기본값 `mysql` 을 쓴다 |
| `REDIS_HOST` | localhost | 재고 서비스가 붙는 Redis. `bootRun` 기준 기본값이고, compose 에서는 이미지 기본값 `redis` 를 쓴다 |
| `PG_BASE_URL` | http://localhost:8099 | 결제 서비스가 부를 결제사 스텁 주소. `bootRun` 기준 기본값이고, compose 에서는 이미지 기본값 `http://pg-stub:8080` 을 쓴다 |
| `PG_STUB_PORT` | 8099 | 결제사 스텁 호스트 포트 |
| `ORDER_BASE_URL` | http://localhost:8091 | 게이트웨이가 넘길 주문 서비스 주소. `bootRun` 기준 기본값이고, compose 에서는 이미지 기본값 `http://order:8091` 을 쓴다 |
| `INVENTORY_BASE_URL` | http://localhost:8093 | 게이트웨이 · 주문 서비스가 부를 재고 서비스 주소. `bootRun` 기준 기본값이고, compose 에서는 이미지 기본값 `http://inventory:8093` 을 쓴다 |
| `PAYMENT_BASE_URL` | http://localhost:8092 | 주문 서비스가 부를 결제 서비스 주소. `bootRun` 기준 기본값이고, compose 에서는 이미지 기본값 `http://payment:8092` 를 쓴다 |
| `MONIMO_OTLP_CERTS_DIR` | (비움 = 평문) | 에이전트 mTLS 인증서 폴더 (backend `#159` · ADR `#64`). backend compose 가 `monimo-backend/certs` 에 만든다. 채우면 네 서비스가 그 안의 `env/shop-<서비스>.env` 를 읽어 `https://collector:4317` 과 파일 셋(`OTEL_EXPORTER_OTLP_CERTIFICATE` 기관 인증서 · `..._CLIENT_CERTIFICATE` · `..._CLIENT_KEY` 자기 이름의 신분증 · 열쇠)을 받고, 폴더를 `/certs` 로 읽는다. 비우면 `otel/agent.properties` 의 평문 `http://collector:4317` 그대로. Collector 주소만 따로 바꾸던 `OTEL_EXPORTER_OTLP_ENDPOINT` 는 `#40` 에서 뺐다(빈 값이 env_file 값을 덮는다) |
| `OTEL_SERVICE_NAME` | (컨테이너별) | `shop-gateway` · `shop-order` · `shop-payment` · `shop-inventory` |
| `MONIMO_AGENT_TOKEN` | local-agent-token | 스레드 덤프 Extension 이 Collector 에 붙이는 토큰 (compose 가 `OTEL_MONIMO_AGENT_TOKEN` 으로 넘김). Collector 값과 같아야 한다 |
| `OTEL_RESOURCE_ATTRIBUTES` | (컨테이너별) | 에이전트 이름표와 환경. compose 는 `service.instance.id=shop-<서비스>-local-1,deployment.environment.name=local`, 쿠버네티스는 Downward API 로 파드 이름 (위 「한 번에 켜기」) |

## 포트

| 서비스 | 포트 | compose 서비스 이름 | 상태 |
|---|---|---|---|
| gateway | 8090 | `gateway` | 입구. 주문 API 는 주문으로, 재고 조회는 재고 서비스로 넘긴다 |
| order | 8091 | `order` | 주문 API |
| payment | 8092 | `payment` | 결제 API |
| inventory | 8093 | `inventory` | 재고 API (MySQL + Redis 캐시) |
| MySQL | 13306 (컨테이너 안 3306) | `mysql` | 볼륨 `shop-mysql-data` (orders · stock · stock_deductions) |
| Redis | 16379 (컨테이너 안 6379) | `redis` | 7.2, 재고 조회 캐시. 데이터를 남기지 않는다 |
| 결제사 스텁 | 8099 (컨테이너 안 8080) | `pg-stub` | WireMock, 에이전트 없음 |

## 관련 문서

- [설계 문서 (결정 기록 원본)](https://github.com/2026-techeer-project-team-b/monimo-backend/tree/main/docs/design): monimo-backend 레포의 `docs/design/`
- [레포별 파일 구성](https://app.notion.com/p/3e1d7d6851ff80a8a110e8aea0b5783b)
- [깃허브 레포지토리 규칙](https://app.notion.com/p/3dcd7d6851ff8000b795f1cc609124e6)

## 기여 규칙

- 브랜치 전략: 기능 브랜치 → `develop`(기본 브랜치, 작업을 모으는 곳) → 배포 단위로 `develop` → `main`
- `main` · `develop` 직접 push 금지, PR로만 머지. PR 의 base 는 기본값(`develop`) 그대로 두면 된다
- PR 마다 CI(`.github/workflows/ci.yml`)가 돈다: **build**(Gradle 컴파일 + 테스트) → **smoke**(compose 로 이미지 빌드 · 기동 후 k6 초당 1건 · 10초, checks 100%)
- 브랜치: `feat/<이슈번호>-<설명>` · `fix/<이슈번호>-<설명>` · `chore/<설명>`
- 커밋: `<타입>(<범위>): <요약>` (타입: feat · fix · docs · chore · refactor · test)

## AI 와 일한 방법 (승조 담당)

이 레포는 승조(`@SeungJo-02`) 담당이고 AI(Claude Code)와 함께 만들었다. 규칙은 [`AGENTS.md`](AGENTS.md), 절차 · 역할 분담 · 토큰 기준값 · AI 가 틀린 것과 잡은 방법은 `monimo-backend` 의 [`docs/seungjo/harness.md`](https://github.com/2026-techeer-project-team-b/monimo-backend/blob/HEAD/docs/seungjo/harness.md)(정본, 한 곳에만 둔다), 이 레포에서 무엇을 어떻게 물었는지는 [`docs/prompts/`](docs/prompts/README.md) 에 있다.
