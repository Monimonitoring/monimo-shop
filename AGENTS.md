# AGENTS.md — 작업을 시작하기 전에 읽는 파일

> **사람이든 AI 도구든, 이 레포에서 무언가를 고치기 전에 이 파일을 먼저 읽는다.**
> 여기에는 레포 구조, 깨면 안 되는 규칙, 지금까지 한 일, 지금 막혀 있는 것이 들어 있다.
>
> 작업을 끝내면 **§5(지금까지 한 일)와 §6(막혀 있는 것)을 갱신해서 같은 PR 에 넣는다.**

## 0. 정본이 어디인가

| 무엇 | 정본 |
|---|---|
| 설계 결정(ADR) | `monimo-backend` 레포 `docs/design/01-decisions.md` |
| 로컬 실행 · API · 포트 · 환경변수 | 이 레포 `README.md` |
| 부하 시나리오 | `k6/README.md` |
| 에이전트 버전 | `otel/AGENT_VERSION` |

## 1. 레포 한눈에

감시 **대상**이 되는 쇼핑몰이다. 제품 기능은 없고, 모니모니터링이 볼 호출 모양을 만드는 것이 목적이다.

Kotlin 2.2 · Java 17 · Spring Boot 3.5 · Gradle 멀티모듈. 버전은 `gradle/libs.versions.toml` 한 곳에서 정하고 `monimo-backend` 와 같은 버전을 쓴다.

| 폴더 | 하는 일 | 포트 |
|---|---|---|
| `gateway/` | 입구. `/api/orders` 는 주문, `GET /api/stock` 은 재고 서비스로 그대로 넘긴다 | 8090 |
| `order/` | 주문. MySQL 에 저장하고 결제를 부른다 | 8091 |
| `payment/` | 결제. 외부 결제사(pg-stub)를 부른다 | 8092 |
| `inventory/` | 재고. MySQL(stock) + Redis 조회 캐시 | 8093 |
| `agent-extension/` | 스레드 덤프 명령 수신 Extension (Java, 의존성 0). Collector `/agent/commands` 롱폴링 → 덤프 회신. 우리가 만드는 유일한 에이전트 코드 | — |
| `otel/` | OTel Java Agent 설정(`agent.properties`)과 버전 고정 | — |
| `k6/` | 부하 · 에러 주입 시나리오 | — |
| `pg-stub/` | 더미 외부 결제사 (WireMock, 에이전트 없음) | 8099 |
| `perf/overhead/` | 에이전트 오버헤드 측정 스크립트 (붙임 · 뗌을 같은 부하로 비교) | — |
| `Dockerfile` (루트) | 네 서비스 이미지를 한 파일로. Gradle 한 번 + 서비스별 `--target`. 서비스를 더하면 build 의 gradlew 줄과 맨 아래 target 단계를 같이 늘린다 | — |

주문 한 건이 만드는 호출: `gateway → order` · `order → MySQL` · `order → payment` · `payment → pg-stub`.

## 2. 깨면 안 되는 규칙

- **앱 코드에 계측을 넣지 않는다.** 트레이스 · 메트릭 · 로그는 이미지에 붙인 OTel Java Agent 가 만든다 (ADR `#33`). 스팬을 직접 만드는 코드를 넣으면 "에이전트만 붙이면 된다"는 제품 전제가 깨진다.
- **서비스 이름은 `shop-gateway` · `shop-order` · `shop-payment` · `shop-inventory`.** Ingester 가 이 이름을 `applications.name` 과 정확히 비교한다 (backend `#83`). 바꾸면 Service map 화살표가 끊긴다.
- **서비스를 추가하면 `OTEL_INSTRUMENTATION_COMMON_PEER_SERVICE_MAPPING` 에 짝을 더한다.** 빠뜨리면 상대가 외부 시스템으로 잡힌다. `pg-stub` 은 넣지 않는다 (진짜 외부로 보여야 한다).
- **`X-Shop-Fault` 의 이름과 값은 k6 와의 약속이다.** 바꾸면 `k6/` 도 같은 PR 에서 바꾼다.
- **API 모양은 Watchdog 카나리와 k6 가 그대로 쓴다.** 경로 · 응답을 바꾸면 `monimo-watchdog` 담당(재범)에게 알린다.
- **에이전트 버전은 `otel/AGENT_VERSION` 한 곳.** Dockerfile 에 버전을 직접 적지 않는다.
- **비밀값은 레포에 올리지 않는다.** `.env.example` 에 이름만 둔다. 레포는 퍼블릭이다.

## 3. 작업 흐름

1. GitHub 이슈를 만든다. 제목은 커밋 형식과 같게 (`feat(order): ...`).
2. 브랜치를 판다. `feat/<이슈번호>-<설명>` · `fix/<이슈번호>-<설명>` · `chore/<설명>`. **`origin/develop` 에서 새로 판다.**
3. 커밋 메시지는 `<타입>(<범위>): <요약>`. 타입은 feat · fix · docs · chore · refactor · test.
4. PR 의 base 는 `develop`. `main` · `develop` 직접 push 는 막혀 있다.

CI 는 **build**(Gradle 컴파일 + 테스트) → **smoke**(compose 로 띄운 뒤 k6 초당 1건 · 10초, checks 100%)를 돈다. smoke 가 깨지면 호출 골격이 깨진 것이다.

## 4. 담당

| 폴더 | 담당 |
|---|---|
| 전체 | 승조 `@SeungJo-02` |
| `agent-extension/` | 승조 · 재범 `@jaebeom79` |

## 5. 지금까지 한 일

- OTel Java Agent 설정 — gRPC · `collector:4317` · 샘플러 always_on · 버전 2.31.1 (`#4`)
- Gradle 멀티모듈 뼈대 5개 모듈 (`#6`)
- 결제 승인 골격과 `X-Shop-Fault` 에러 · 지연 주입 (`#8` 1단계), 주문 API 와 MySQL 저장 · 결제 호출 (`#8` 3단계), 컨트롤러 테스트 6건 (`#8` 5·6단계)
- order · payment Dockerfile 에 에이전트 부착 (`#12`), `docker-compose.dev.yml` 로 한 번에 실행하고 `monimo-dev` 네트워크로 Collector 연결 (`#14`)
- k6 주문 부하 + 에러 · 지연 주입 시나리오 (`#16`), PR 마다 build + smoke CI (`#18`)
- 더미 외부 결제사 pg-stub 호출과 `pg-error` · `pg-slow` 주입 (`#20`)
- Service map 에서 order → payment 가 외부로 잡히던 문제를 peer-service-mapping 으로 해결 (`#23`)
- 얇은 게이트웨이. 진입 주소를 8090 으로 (`#25`)
- 에이전트 이름표 `service.instance.id` 를 환경변수로 고정. compose 는 `shop-<서비스>-local-1`, 쿠버네티스는 Downward API 로 파드 이름 (`#30`). 재시작해도 `agent_id` 가 그대로다
- 스레드 덤프 Extension 첫 검증 (`#34`, backend `#122`). Java Extension 이 Collector 에 롱폴링으로 명령을 받아 jstack 모양 덤프를 회신. 네 서비스 모두 덤프 200, Extension 호출 스팬 0건, Collector 재시작 4~5초 뒤 재연결, 별도 리뷰 MEDIUM 4건 반영
- 이미지 빌드를 가볍게 (`#42`). 서비스마다 있던 Dockerfile 4개를 루트 `Dockerfile` 하나(멀티스테이지 · 서비스별 target)로, Gradle 캐시 마운트, `.dockerignore` 에 `docs` · `perf` · `*.md`. 캐시 없이 97 → 40초(도커 CPU 최고 918 → 662%), 코드 한 줄 뒤 약 94 → 8초, 문서만 바뀌면 94 → 1초
- 에이전트 오버헤드 측정 (`#36`). 같은 이미지 · 고정 부하로 붙임 · 뗌 5회씩 번갈아(CPU 1개 · 1GB 제한). CPU 상대 +10 ~ +39%(1코어 대비 +0.6 ~ +3.6%p, 주문 1건당 +2.9 ~ +4.3ms), 메모리 +80 ~ +141MB 로 요구사항(3% · 100MB) 못 지킴. 메모리 절반은 힙 아닌 쪽(에이전트 클래스 약 5,000개 · JIT 코드, 고정), 샘플링 10% 는 효과 없고 메트릭 끄기가 에이전트 추가 CPU 의 약 5분의 1 로 보임(3회, 확정 아님). 스크립트 `perf/overhead/`, 결과 `docs/seungjo/36-agent-overhead/results.md` 요구사항은 팀 결정으로 실측에 맞게 고침(backend `#132` · ADR `#56`, `#38`)
- 오버헤드를 **줄이는 법** 측정 (`#51`, 멘토 피드백 C · `#36` 후속). 변형 여섯(메트릭 전송 간격 30 · 60초, 앱 쪽 샘플링 10 · 1%, 배치 묶음 1024, 배치 주기 10초)을 **85회차** 돌렸다(초당 20건 70 · 초당 40건 15, 반복마다 순서를 한 칸 돌림). 결과 : **CPU 는 넷 다 못 줄인다** (주문 1건당 CPU 가 뗌 16.61ms → 그대로 19.98ms 로 +3.37ms · +20% 인데 붙임 변형 여덟의 회차 폭 18.5 ~ 28.6ms 가 전부 겹치고, 초당 40건으로 폭을 좁혀도 전송 간격 30초가 그대로 둔 것의 폭 안에 들어간다). 전송량은 설정대로 줄었다(메트릭 쇼핑몰 몫 558 : 186 : 93 = 간격의 역수, 스팬 20,608 → 226 으로 약 91배) → **비용은 보내기가 아니라 계측 자체에 있다**. `#36` 의 메트릭 끄기(적는 것까지 끔)가 약 5분의 1 을 가져간 것과 합치면 **적는 일은 비용이고 보내는 일은 아니다**. 메모리는 **계측 범위 줄이기만 약 20MB** 줄었고(`#36` 의 10 ~ 20MB 를 3회차 → 5회차로 굳힌 확인), 그 대가로 메트릭 84건이 빠진다. 결과 `docs/seungjo/51-overhead-knobs/results.md`
- 재고 서비스 + Redis 캐시 (`#32`). 주문이 결제 전에 재고를 조건부 UPDATE 로 차감하고 모자라면 409, 결제 실패면 복원(주문 번호로 한 번만). 조회만 cache-aside, Redis 헬스체크는 끔(orphan span), timeout 500ms + DB 폴백. k6 에 재고 조회 손님 · inventory-slow · inventory-error · 품절 추가
- 에이전트 mTLS (`#40`, backend `#159` · ADR `#64`). Collector 가 손님 신분증을 요구할 때 네 서비스가 자기 이름의 신분증을 내도록 compose 에 OTel 인증서 환경변수 셋과 `/certs` 마운트. `.env` 의 `MONIMO_OTLP_CERTS_DIR` 이 비어 있으면(기본) 평문 그대로. 자바 코드 0줄
- `AGENTS.md` · `CLAUDE.md`, README 「AI 와 일한 방법」 절, `docs/prompts/`(프롬프트 로그 : 코드와 같은 PR 에). 하네스 정본은 backend `docs/seungjo/harness.md` 한 곳
- 2026-10-26 문서 · 주석의 우리말 조어를 현업 이름으로 바꿨다 (backend `#240` · ADR `#73`).
- 2026-10-26 코드 안 문자열도 같은 이름으로 바꿨다 (backend `#241`) : 테스트 이름 · Extension 로그 문구(`Collector 가 명령 폴링에 …`)

## 6. 지금 막혀 있는 것

| 무엇 | 안 풀면 |
|---|---|
| 에이전트는 2.x 에 머문다 (3.0 은 올리지 않기로 함, `#47`) | 막힌 것은 아니다. 3.0 은 DB 호출 Attribute 를 `db.system` 에서 `db.system.name` 으로 바꾸는데 backend `mv_server_map_1m` 은 `db.system` 만 봐서, 올리면 MySQL · Redis 가 Service map 에서 외부로 잘못 그려진다. **올리게 되면 먼저** 수집 쪽 집계가 두 키를 다 보게 한다 (`#32` research 2.2) |
| 스레드 덤프 API server 팬아웃 · 화면 · CH 저장이 없다 | Collector `POST /internal/thread-dump` 까지만 있다. API server 팬아웃(`agentUuid` → service · instance, 503 재시도)은 재범, CH `thread_dumps` 저장(Q16)은 조회 쪽. 그 전까지는 curl 로만 뜬다 |
| 에이전트 오버헤드 기준이 빠듯하다 | 요구사항을 실측에 맞게 고쳤다(backend ADR `#56` : 파드당 CPU 1개 · 초당 주문 20건에서 1코어 대비 +3%p, 메모리 150MB). 지금 값은 통과(+1.4 ~ +2.1%p, 최대 141MB)지만 초당 40건이면 order · inventory 가 +3.5 · +3.6%p 로 넘고 메모리 여유는 9MB 다. 실제 파드 부하가 정해지거나 에이전트 버전을 올리면 `perf/overhead/` 로 다시 잰다 (`#36`). **`#51` 이 「설정으로 줄이는 길」을 닫았다** : 전송 간격 · 샘플링 · 배치로는 CPU 가 안 줄어 기준을 못 지키게 되면 **파드 limit 을 올리거나 계측 범위를 줄이는 것**뿐이다 |
| 계측 범위 줄이기를 운영에 넣을지 못 정했다 (`#51`) | 메모리를 서비스마다 약 20MB 줄이지만 **메트릭 84건이 빠진다**(`#36` 이 짚은 hikaricp 커넥션 풀 지표 · 로그 MDC 의 `trace_id` 주입). 넣기 전에 셋을 확인한다 : ① 10회차로 다시 재기(지금 5회차 · 폭 간격 5 ~ 11MB) ② ClickHouse 에서 빠진 지표 이름 대조 ③ 로그 문자열의 `trace_id`. 넣으면 켜 둔 목록이 계약이 되어 **새 기술(Kafka · 외부 API)을 붙일 때마다 추가해야 하고 빠뜨리면 그 구간이 트레이스에서 조용히 사라진다** |
| Redis 연결을 맺을 때 부모 없는 스팬 3개(`HELLO` · `CLIENT SETINFO` ×2)가 남는다 (의도적으로 둠, `#47`) | Lettuce(Redis 드라이버)가 연결을 맺으며 보내는 인사라 감쌀 요청(SERVER 스팬)이 없다. 앱 시작 · 재연결 때만 3개씩이라 그대로 둔다. 헬스체크 `INFO` 고아는 `#32` 에서 재고 서비스의 Redis 헬스체크를 꺼서 없앴다 : 캐시 하나 때문에 재고 서비스 probe 가 실패하지 않게 하려는 것도 같은 이유다. Redis 상태는 Redis 자신의 probe(`redis-cli ping`)와 APM Service map(inventory → Redis 간선 에러)으로 본다 |

## 7. 참고

- 설계 문서: https://github.com/2026-techeer-project-team-b/monimo-backend/tree/HEAD/docs/design
- Collector 까지 데이터를 보내려면 `monimo-backend` 에서 `docker compose --profile collector up -d --wait`
