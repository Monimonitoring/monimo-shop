# 2026-10-07 스레드 덤프 Extension 첫 검증

> 용어만 glossary(`monimo-backend/docs/design/glossary.md`) 기준으로 바꿈 (`#240`) : 원문 인용도 용어만 바꾸고 문장은 그대로 뒀다

- 관련 이슈 : `#34` (쇼핑몰) · backend Collector `/agent/**` 이슈 (만들 예정)
- 쓴 도구 : 웹 리서치 서브에이전트 1개, backend ADR `#31` `#33` `#36` · 노션 「스레드 덤프 설계 1차 최종 정리」 · 재범 「스레드 덤프 명령 전달」 읽기, Collector 코드 확인
- 결과물 : `agent-extension/` Java Extension, Dockerfile 4개 · compose, backend `#122` Collector 문 셋. 설계 한 장은 [`README.md`](README.md)

## 프롬프트

시작 (승조, 원문) :

```
1,2번은 끝났고 3번은 pass 할거고 4번도 정리 해놨다 
다음 작업 관련해서 준비해줘
```

(앞 대화의 "다음 해야할 것 리스트" 5번 = 스레드 덤프 Extension 첫 검증)

조사 단계 (메인 대화 → 웹 리서치 서브에이전트, 원문) :

```
조사 질문 (출처 링크 필수. 순서: 공식 문서 → 오픈소스 코드 → 블로그. 링크 못 찾은 주장은 "미확인"). 한국어, 질문 번호별로 짧게, 끝에 확신도.

배경: Kotlin/Spring Boot 3.5 · Java 17 쇼핑몰 서비스에 OpenTelemetry Java Agent 2.31.1 을 -javaagent 로 붙인다(앱 코드는 에이전트를 모른다). 우리 APM 백엔드(Collector, Kotlin/Spring)에 "스레드 덤프" 기능을 만들려 한다: 화면에서 버튼을 누르면 → API server 가 모든 Collector 에 팬아웃 → Collector 는 그 에이전트가 자기에게 롱폴링 중이면 명령을 돌려줌 → 에이전트 쪽 Extension 이 ThreadMXBean.dumpAllThreads() 로 덤프를 떠서 Collector 에 POST. 합의: HTTP 롱폴링(GET /agent/commands 최대 25초 대기, POST /agent/commands/{id}/result), 인증은 에이전트 전용 토큰 헤더, 에이전트 식별은 service.name + service.instance.id. Extension 은 OTel Java Agent 의 extension jar(-Dotel.javaagent.extensions=/path/ext.jar) 로 만들며 Spring 없이 순수 Kotlin/Java.

알고 싶은 것:
1. OTel Java Agent extension 만드는 법 (2.x): 필요한 의존성(opentelemetry-javaagent-extension-api, autoconfigure-spi, @AutoService), 배경 작업을 시작하기 좋은 SPI 훅 — AgentListener.afterAgent(AutoConfiguredOpenTelemetrySdk) 가 맞나? 이게 앱 main 전에 불리나, 에이전트 설정(ConfigProperties)을 어떻게 읽나(otel.* 커스텀 키, 환경변수 매핑). 공식 examples 레포(opentelemetry-java-instrumentation/examples/extension) 링크와 build.gradle 핵심.
2. extension 안에서 HTTP 요청을 보낼 때 (java.net.http.HttpClient 또는 HttpURLConnection) 에이전트가 그 호출을 자동 계측해 스팬을 만드나? 만들면 "에이전트 자신의 롱폴링" 이 트레이스로 수집되는 문제가 생긴다. 끄는 방법: 특정 스레드/컨텍스트 억제(Context.current().with(...)? `io.opentelemetry.javaagent.bootstrap.CallDepth`? `SuppressInstrumentation`?), 또는 extension 클래스로더에서 로드된 HttpClient 는 계측되지 않는 규칙이 있나(agent classloader 격리). 정확한 근거.
3. ThreadMXBean.dumpAllThreads(lockedMonitors, lockedSynchronizers) 비용과 안전 모드(safepoint, 스레드 수 수백 개일 때 ms 단위), 출력 포맷을 jstack 과 비슷한 텍스트로 만드는 흔한 코드. Java 17 기준. 가상 스레드는 안 다룸.
4. 롱폴링 구현 요점: 에이전트 쪽 단일 데몬 스레드 루프, 타임아웃(서버 25초 + 클라이언트 30초), 연결 실패 시 지수 백오프 상한, Collector 재시작 시 재연결, JVM 종료 때 데몬 스레드라 안 막는지. 서버 쪽(Spring MVC) 롱폴링: DeferredResult 또는 ResponseBodyEmitter 로 스레드 안 잡고 대기하는 법, 타임아웃 시 204 반환 패턴.
5. 에이전트가 Collector 여러 대 중 하나에만 붙는 구조에서, 다른 Collector 가 "내 에이전트 아님" 을 알리는 503 패턴과 API server 팬아웃 재시도 — Pinpoint 는 어떻게 하나 (ActiveThreadDump, agent 가 collector 와 gRPC 양방향 스트림 유지, web → collector 는 Redis pub/sub 또는 cluster 로 라우팅). 출처.
6. OTel 자체에 스레드 덤프/프로파일 표준이 있나: OTLP profiles 신호(experimental), JFR, 혹은 "agent remote command" 개념이 있나(없으면 없다고). opamp(OpAMP) 프로토콜이 에이전트 원격 제어 표준인지, Java agent 가 OpAMP 를 지원하나.
7. 보안: 에이전트 전용 토큰을 env 로 주고 헤더로 보내는 패턴, 덤프 본문 크기(스레드 300개 × 스택 50줄 ≈ 몇 KB~MB), 압축 필요 여부.
```

구현 단계 (research 8 절, 승조 결정 원문. AI 초안에서 스택 상한 · 연타 보호 값을 승조가 바꿈) :

```
스레드 덤프 Extension 첫 검증은 추천 방향으로 가자

Extension은 Java로 만들자 바깥 라이브러리 0개라 jar가 작고 에이전트 안에서 클래스 충돌 걱정이 없다
Kotlin은 레포 언어는 맞출 수 있지만 kotlin 기본 라이브러리를 jar에 넣고 이름 바꿔 묶어야 해서 합의안의 의존성 0개가 깨지니까 패스

afterAgent는 앱 main보다 먼저 main 스레드에서 불리니까 거기서는 데몬 스레드 하나만 띄우고 바로 return 하자
식별 값은 sdk.getResource()가 막혀 있으니 addResourceCustomizer로 service.name, service.instance.id를 읽자
일꾼 스레드의 HTTP 호출은 전부 suppressInstrumentation으로 감싸서 스팬이 안 남게 하자 (시험에서 감싸면 0개 안 감싸면 2개 확인)

덤프는 jstack처럼 직접 글로 만들자 toString()은 스택이 8줄에서 잘려서 깊은 호출에서 우리 코드 줄이 안 보일 수 있다
대신 스택 깊이는 128줄까지로 상한을 두자
JSON 구조는 화면이랑 CH thread_dumps 모양이 정해지면 그때 바꾸는 거로

결과 보내기가 실패하면 회신 주소로 1초 간격 3번까지 다시 보내고 그래도 안 되면 로그만 남기고 버리자
다른 Collector 로 보내는 건 그 Collector 가 명령을 기다리는 요청이 없어서 의미가 없으니 패스

명령은 command_id, type, reply_to, timeout_ms 넣고 결과는 service, instance, taken_at, elapsed_ms, thread_count, format, dump 넣자
type이 THREAD_DUMP가 아니면 무시하고 토큰은 X-Monimo-Agent-Token 헤더로 보내자

Collector 에는 진짜 팬아웃 받는 문 POST /internal/thread-dump까지 이번에 만들어서 curl로 시험하자
그래야 시험한 게 곧 진짜 경로고 재범님이 API server 만들 때 Collector 쪽이 이미 있다
Collector 25초 대기는 끝날 때 204를 명시하자 기본 503이 "내 에이전트 아님"이랑 겹친다

같은 에이전트에 10초 안에 덤프가 또 오면 직전 결과를 다시 보내자 연타해도 JVM이 한 번만 멈추게

Collector 쪽은 backend에 따로 이슈 만들고 두 레포를 같이 띄워서 E2E 시험하자
확인할 것: 명령 넣으면 25초 안에 덤프가 돌아오는지, ClickHouse에 /agent/commands 스팬이 0건인지, Collector 재시작하면 Extension이 다시 붙는지
```

## 결과 (조사 단계)

- 한 번에 됐나 : 예 (되물음 0회)
- 조사 답이 바로잡은 것 : 질문 2 의 "extension 클래스로더면 계측 안 되지 않나" 는 틀린 기대였다. JDK HttpClient 는 누가 부르든 계측되고, 끄는 공식 스위치는 `InstrumentationUtil.suppressInstrumentation` 이다
- 조사 답이 못 확인한 것 : `api.impl` 패키지가 Extension 로더의 remap 범위에 드는지(실측 필요), `dumpAllThreads` 의 ms 수치, 덤프 압축률
- 조심할 것 : 공식 예제 main 브랜치가 3.0 기준이라 `v2.31.1` 태그를 봐야 한다

## 결과 (구현 단계)

- 한 번에 됐나 : 거의 (사람이 고쳐 물은 것 0)
- 막힌 것 : 컨트롤러 테스트에서 MockMvc 는 비동기 타임아웃을 스스로 일으키지 않아 "25초 뒤 204" 를 시험할 수 없었다. 서블릿 컨테이너가 하는 일(`AsyncListener.onTimeout`)을 테스트가 대신 불러 해결
- E2E 시험 방식 : 사용자가 띄워 둔 Collector 를 바꾸지 않으려고, 워크트리로 만든 Collector 를 `collector-122` 라는 별도 컨테이너로 띄우고 쇼핑몰만 그쪽을 보게 했다(시험용 compose override). OTLP 는 원래 Collector 로 그대로 가서 ClickHouse 확인이 가능했다
- E2E 수치 : README 「어떻게 확인했나」

## 다섯 칸은 어디에 있었나

| 칸 | 프롬프트에 | 다른 곳에 |
|---|---|---|
| 목표 | 있음 (첫 검증) | 이슈 `#34` · `#122` |
| 배경 | 있음 (선택지마다 버린 이유) | research 2 · 5 절 |
| 범위 | 있음 (backend 따로 이슈) | research 1 절 표 |
| 제약 | 있음 (의존성 0, 204, 토큰 헤더) | 합의안 6가지 |
| 완료 기준 | **있음** (확인할 것 세 줄, `#32` 회고 반영) | — |
