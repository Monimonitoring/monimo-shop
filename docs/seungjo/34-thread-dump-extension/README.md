# `#34` 스레드 덤프 Extension 첫 검증

> **이 작업의 기술 설계 문서 한 장.** 문제 → 선택지 → 결정 → 장애가 나면 순서로 읽으면 끝난다.
> 하네스 규칙과 틀은 backend [`docs/seungjo/`](https://github.com/2026-techeer-project-team-b/monimo-backend/blob/HEAD/docs/seungjo/README.md) 가 정본이다. 쇼핑몰 쪽(Extension)과 backend 쪽(Collector 문, `#122`)을 같이 만들었고 조사 · 결정은 이 폴더 하나에 둔다.

- 2026-10-07 ~ 08 / 승조(`@SeungJo-02`) / 결정 ADR `#31` · `#33` 구체화 (롱폴링 합의안 6가지) / 이슈 쇼핑몰 `#34` · backend `#122`
- 유저 플로우에서 어디 : **에이전트 안**(Extension) ↔ **Collector**. 화면 → API server 팬아웃 → Collector → Extension → 덤프 → Collector → API server 중 가운데 구간

## 문제

화면의 "스레드 덤프" 버튼이 쇼핑몰 JVM 까지 갈 길이 없었다. `agent-extension/` 은 1단계부터 빈 모듈이었고, Collector 에는 에이전트가 명령을 받으러 올 문이 없었다. ADR `#33` 되돌림 (a) : 1b 종료까지 동작하지 않으면 스레드 덤프를 범위에서 빼고 명령 채널(`#31`)도 폐기한다.

| 재 본 것 | 값 |
|---|---|
| `agent-extension/` | `build.gradle.kts` · README 만 |
| Collector HTTP 문 | `/actuator/**` 뿐. `/agent/**` · `/internal/**` 없음 |

**무엇이 깨지나** : 핵심기능 3 "스레드 덤프를 화면에서 본다" 가 빠진다.

## 선택지

여섯 가지. 전체 표 · 근거는 [`research.md`](research.md) 5 절.

| 항목 | 고른 것 | 버린 것과 이유 |
|---|---|---|
| ⓐ 언어 | **Java** (의존성 0, jar 19 KB) | Kotlin : kotlin-stdlib 를 넣고 relocate 해야 해 "의존성 0개" 합의가 깨짐 |
| ⓑ 덤프 모양 | **jstack 모양 직접**, 스택 128줄 상한 | `ThreadInfo.toString()` : 8줄에서 잘림. JSON : 화면 · CH 모양과 같이 정해야 함 |
| ⓒ 결과 실패 | **회신 주소로 1초 × 3회** | 다른 Collector 로 : 기다리는 요청이 없어 무의미 |
| ⓓ 모양 | `{command_id, type, reply_to, timeout_ms}` / `{service, instance, taken_at, elapsed_ms, thread_count, format, dump}` | — |
| ⓔ 시험 경로 | **Collector 에 진짜 팬아웃 문 `/internal/thread-dump` 까지** | 임시 문 · 단위 테스트만 : 진짜 경로를 다시 시험해야 함 |
| ⓕ 연타 | **10초 안이면 직전 결과 재사용** | 막지 않음 : 누를 때마다 JVM 이 멈춤 |

## 결정

- **고른 것** : 위 표. 토큰 헤더 `X-Monimo-Agent-Token`. Collector 폴링 25초 대기 끝은 **204** 명시(기본 503 이 "내 에이전트 아님" 과 겹침)
- **버린 것과 이유** : 표 오른쪽 칸
- **되돌리는 조건** : ADR `#31` 그대로 (Collector 10대 초과 또는 팬아웃으로 명령 응답 p95 증가가 실측되면 Redis TTL 레지스트리로). 화면 · CH 모양이 정해지면 덤프 모양을 JSON 으로

결정 원문은 [`research.md`](research.md) 8 절과 [`prompts.md`](prompts.md).

## 장애가 나면

| 무엇이 죽으면 · 틀리면 | 어떻게 되나 | 어떻게 알아채나 |
|---|---|---|
| Collector 가 꺼짐 · 재시작 | Extension 은 1 → 60초 백오프로 다시 묻는다. 쇼핑몰은 정상. 실측 : 재시작 **5초** 뒤 다시 덤프 200 | 팬아웃이 전부 503 |
| 토큰이 어긋남 | Collector 가 401, Extension 은 백오프하며 경고 로그 | 쇼핑몰 로그 `[monimo] … 401` |
| Extension 토큰이 비어 있음 | Extension 이 아예 안 뜬다(로그 한 줄) | 쇼핑몰 로그 `[monimo] … 꺼짐` |
| `suppressInstrumentation` 이 안 먹음 | 25초마다 `/agent/commands` 스팬이 ClickHouse 에 쌓이고 Service map 에 `collector:8081` 외부 노드 | 아래 「어떻게 확인했나」의 조회 |
| Collector 폴링 대기 끝에 204 를 안 줌 | 기본 503 이 나가 API server 가 "내 에이전트 아님" 과 구분 못 함 | 컨트롤러 테스트가 고정 |
| 결과 POST 가 다른 Collector 로 감 | 그 Collector 는 명령을 모르니 404, 기다리던 쪽은 504 | Collector 가 여러 대일 때만. `reply_to` 로 막았다 |
| 덤프 연타 | 10초 안은 직전 결과(같은 `taken_at`) | 결과의 `taken_at` |
| 같은 이름표로 JVM 이 둘 뜸 (compose `--scale` 등 잘못된 설정) | 서로 밀어내며 409, Extension 이 백오프. 덤프는 그 순간 쥔 쪽 것이 나온다 | 쇼핑몰 로그 409 경고 |
| 힙이 큰 JVM | 덤프가 락 synchronizer 를 찾느라 힙을 훑어 멈춤이 길어질 수 있다(리뷰 지적, 미실측). 스레드 42개 · 작은 힙에서 66 ms | 결과의 `elapsed_ms` |
| 에이전트 토큰이 하나뿐이고 이름표는 스스로 주장한다 | 털린 서비스 하나가 다른 서비스 이름표로 폴링해 명령을 가로챌 수 있다. 내부 문은 못 연다 | 후속 : 에이전트별 토큰 · mTLS(FN-12) |

## 어떻게 확인했나

- 단위 테스트 : 쇼핑몰 agent-extension 18건 (명령 · JSON · 덤프 모양 · 10초 재사용 · 백오프 · 가짜 Collector 로 204 · 200 · 무시 · 읽을 수 없는 200 · 409 · 401 · 결과 재시도 · 404), backend collector 16건 (보관소 10 : 보유 · 재폴링 409 · 빈틈 2초 · 막 끝난 폴링 · 빈틈 중복 · 결과 · 모르는 명령, 컨트롤러 6 : 401 · 문 닫힘 · 대기 끝 204 · 503 · 404)
- E2E : backend 워크트리로 만든 Collector(`collector-122`)와 쇼핑몰 compose 를 같이 띄움 (사용자 Collector · Kafka · PG · ClickHouse 는 그대로)
  - 네 서비스 `POST /internal/thread-dump` → **전부 200, 0.15 ~ 0.33초**. order : 스레드 42개, 덤프 66 ms, 본문 38 KB
  - 없는 에이전트 503 · 내부 토큰 틀림 401 · 에이전트 토큰으로 내부 문 401 · 토큰 없는 폴링 401
  - 연타 : 0초 · 3초 결과의 `taken_at` 같음, 12초 뒤 새 값
  - Collector 재시작 → **5초** 뒤 order 덤프 200, 네 서비스 모두 다시 붙음 (리뷰 반영 뒤 다시 : 4초, 네 서비스 200 · 0.11 ~ 0.33초)
  - k6 (초당 2건 · 10초) 와 15분 넘게 폴링이 돈 뒤 ClickHouse : shop 스팬 305개 중 `/agent/` · collector 관련 **0개** (리뷰 반영 뒤 30분 창 : 309개 중 0개)
- 별도 리뷰(code-reviewer) : APPROVE, 고칠 것 MEDIUM 4건을 반영했다. ① 같은 이름표로 폴링하는 JVM 둘이 204 로 서로를 끊는 고속 루프 → 밀려난 폴링은 409, Extension 은 409 에서 백오프 ② Collector 가 아닌 곳이 200 을 주면 즉시 재질의 루프 → 읽을 수 없는 200 은 백오프 ③ 폴링이 막 타임아웃으로 끝난 몇 ms 에 온 명령이 503 → 빈틈으로 처리 ④ poll 과 dispatch 사이 경합으로 보관 명령이 묻힘 → `CommandHub` 를 synchronized. 같이 : 일꾼 루프가 `Throwable` 을 잡고 같은 실패는 한 번만 WARNING, 빈틈 명령 덮어쓰기 금지, 오래된 키 정리, compose `:-` 주석, jar 크기 표기(33 KB 는 압축 전, 실제 19 KB)
- 시험 Extension (research ⑤) : `afterAgent` 가 앱보다 먼저 main 에서 불림, 감싸면 스팬 0 · 안 감싸면 2
- CI : PR 에서 build · smoke (smoke 는 Collector 가 없어 Extension 이 백오프만 한다)

## 결과물

- 쇼핑몰 새 파일 : `agent-extension/src/main/java/…` (ExtensionConfig · ConfigCapture · ThreadDumpAgentListener · CommandPoller · ThreadDumper · Command · Json · Backoff), `META-INF/services` 2개, 테스트 4개, 이 폴더
- 쇼핑몰 수정 : `agent-extension/build.gradle.kts` · README, `gradle/libs.versions.toml`(OTel compileOnly), Dockerfile 4개, `docker-compose.dev.yml`, `.env.example`, `README.md`, `AGENTS.md`
- backend `#122` : `collector/.../command/` (CommandHub · AgentCommandProperties), `inbound/agent/AgentCommandController`, `application.yml`, 테스트 2개, `compose.yaml`, `.env.example`, AGENTS.md

## 읽는 순서

1. [`prompts.md`](prompts.md)
2. [`research.md`](research.md)
3. [`../../../agent-extension/README.md`](../../../agent-extension/README.md)

## 이 이슈에서 배운 것 (세 줄)

- 에이전트는 Extension 이 보내는 HTTP 도 기록한다. 공식 스위치(`suppressInstrumentation`)를 쓰고, 스팬 0건을 데이터로 확인해야 끝난다
- Extension 의 시작점은 앱보다 먼저 main 에서 불린다. 거기서 기다리면 쇼핑몰이 안 뜬다
- 위험한 부분은 조사 답을 믿기 전에 클래스 2개짜리 시험 Extension 으로 먼저 돌려 봤다. getResource() 가 막혀 있다는 것도 거기서 나왔다
