# 리서치 : 스레드 덤프 Extension 첫 검증

> 용어만 glossary(`monimo-backend/docs/design/glossary.md`) 기준으로 바꿈 (`#240`) : 원문 인용도 용어만 바꾸고 문장은 그대로 뒀다

- 날짜 2026-10-07 / 관련 이슈 `#34` (쇼핑몰) · backend Collector 이슈 (만들 예정)
- 쓴 도구 : 웹 리서치 서브에이전트 1개 · backend ADR `#31` `#33` `#36` · 노션 「스레드 덤프 설계 1차 최종 정리」 · 재범 「스레드 덤프 명령 전달」 · Collector 코드 읽기

> **읽는 순서가 곧 쓰는 순서다.** 1 → 2 를 쓰고 멈춰서 승조가 3 을 묻는다. 답하고 4 를 쓴다.
> 5 를 쓰고 멈춰서 승조가 6 을 묻는다. 답하고 7 을 쓴다. 8 은 승조가 쓴다.
> **3 · 6 · 8 은 사람 차례다.** 비어 있으면 아직 거기까지 온 것이고, 대신 채우지 않는다.

---

## 1. 이번 작업은 무엇을 하는 일인가

- **지금 무엇이 잘못됐나** : 화면의 "스레드 덤프" 버튼이 눌려도 쇼핑몰 JVM 까지 명령이 갈 길이 없다. `agent-extension/` 은 빈 모듈이고 Collector 에는 에이전트가 명령을 받으러 올 문이 없다.
- **그래서 무엇을 만드나** : 에이전트에 얹는 Extension 하나. 뜨자마자 Collector 에 "명령 있어?" 를 걸어 두고(롱폴링, 최대 25초), 명령이 오면 JVM 의 스레드 덤프를 떠서 명령에 적힌 Collector 주소로 돌려보낸다. Collector 에는 그 문 둘(`GET /agent/commands` · `POST /agent/commands/{id}/result`)을 연다.
- **안 하면 어떻게 되나** : 핵심기능 3 "스레드 덤프를 화면에서 본다" 가 빠진다. ADR `#33` 되돌림 (a) : 1b 종료까지 동작하지 않으면 기능 제외 + 명령 채널(`#31`) 폐기.

| | |
|---|---|
| 건드리는 모듈 · 파일 | 쇼핑몰 `agent-extension/` (빈 모듈 → Extension jar), 서비스 Dockerfile 4개(`-Dotel.javaagent.extensions`), compose(Collector 주소 · 토큰 env). backend `collector/` (`/agent/**` 문 둘, 토큰 검사, 보유 판정, 명령 보관) |
| 건드리지 않는 것 | 쇼핑몰 앱 코드(ADR `#33` 비침습), OTel 에이전트 설정 `otel/agent.properties`, API server 팬아웃(재범 · `#36`), 화면, ClickHouse `thread_dumps` 저장(조회 · Q16) |
| 이 이슈 범위 밖 (후속) | API server → Collector 팬아웃(`POST /internal/thread-dump`)과 503 재시도, 화면 버튼, CH 저장, mTLS(FN-12), 알림 때 자동 덤프 |

**조사 들어가기 전에 내가 알던 것 (세 줄)** : 

- 
- 
- 

**알고 싶었던 것**

① Extension 은 어떻게 만들고 어디서 배경 스레드를 시작하나 ② Extension 의 HTTP 호출이 트레이스로 잡히지 않게 하는 법 ③ `ThreadMXBean` 덤프의 비용과 포맷 ④ 롱폴링을 양쪽(Extension · Collector)에서 어떻게 짜나 ⑤ Collector 가 여러 대일 때 보유 판정과 503 ⑥ OTel 에 이미 있는 표준은 없나(OpAMP · profiles) ⑦ 토큰과 덤프 크기

## 2. 작업하기 전에 알아야 하는 것

### 2.1 Extension : 에이전트에 얹는 작은 jar

OTel Java Agent 는 우리가 고치지 않는 기성품이다(ADR `#33`). 대신 **Extension** 이라는 jar 를 옆에 두면 에이전트가 뜰 때 같이 읽어 준다. 붙이는 법은 java 명령줄 한 줄이다.

```
java -javaagent:/app/otel/opentelemetry-javaagent.jar \
     -Dotel.javaagent.extensions=/app/otel/agent-extension.jar \   ← 이 줄이 늘어난다
     -Dotel.javaagent.configuration-file=/app/otel/agent.properties \
     -jar app.jar
```

우리 레포의 `agent-extension/` 모듈이 이 jar 가 된다. 지금은 `build.gradle.kts` 와 README 만 있는 빈 모듈이다. 쇼핑몰 네 서비스 Dockerfile 이 이 jar 를 이미지에 복사해 넣고 `ENTRYPOINT` 에 위 줄을 더한다.

Extension 안에서 에이전트가 주는 API(`AgentListener`, `ConfigProperties` 등)는 **빌드할 때만** 있으면 되고 실행 때는 에이전트가 가지고 있다(`compileOnly`). 그래서 우리 jar 에는 우리 코드만 들어가고, HTTP 는 JDK 에 든 `java.net.http.HttpClient` 를 쓰므로 바깥 라이브러리가 0개다(합의안 "의존성 0개"). 공식 예제는 `examples/extension` 인데 **main 브랜치는 이미 3.0 기준**이라 우리 버전 태그 `v2.31.1` 의 것을 봐야 한다.

### 2.2 배경 스레드를 어디서 시작하나 : `AgentListener.afterAgent`

에이전트는 설치가 끝나면 등록된 `AgentListener` 들의 `afterAgent(sdk)` 를 차례로 부른다. 우리 Extension 은 여기서 데몬 스레드 하나를 띄우고 **바로 return** 한다. 이유 :

- Java 17 에서는 이 호출이 `premain` 안에서 동기로 일어난다. 즉 **앱의 `main` 보다 먼저**다. 여기서 Collector 응답을 기다리면 쇼핑몰 기동이 그만큼 늦어진다
- 데몬 스레드는 JVM 이 끝날 때 기다려 주지 않는다. JDK `HttpClient` 가 안에서 만드는 스레드들도 데몬이라, 우리 스레드만 데몬이면 쇼핑몰 종료를 막지 않는다

등록은 `META-INF/services/io.opentelemetry.javaagent.extension.AgentListener` 파일에 클래스 이름을 적는 것이다(`@AutoService` 애노테이션이 대신 만들어 준다).

### 2.3 에이전트 설정값을 읽는 법 : `otel.` 로 시작하는 키

Collector 주소와 토큰을 Extension 에 알려 줘야 한다. 에이전트 설정 체계를 그대로 타면 된다 : `otel.monimo.collector.url` 같은 키를 정하면 환경변수 `OTEL_MONIMO_COLLECTOR_URL` 로 넣을 수 있다(점 · 줄표 → 밑줄, 대문자). `#30` 에서 본 세 자리(`-D` > 환경변수 > `agent.properties`) 규칙이 그대로다. Extension 은 `AutoConfigurationCustomizerProvider` 의 `addPropertiesCustomizer` 에서 `ConfigProperties` 를 받아 `getString("otel.monimo.collector.url")` 로 읽는다.

에이전트 식별(합의안 5)은 설정이 아니라 **에이전트가 최종으로 만든 Resource** 에서 읽는다 : `service.name`(`OTEL_SERVICE_NAME`) 과 `service.instance.id`(`#30` 으로 `shop-order-local-1` 또는 파드 이름). 이 둘이 백엔드 `agent_id` 와 같아야 보유 판정이 맞는다.

### 2.4 가장 조심할 것 : Extension 의 HTTP 호출도 트레이스로 잡힌다

JDK `HttpClient` 는 에이전트가 자동 계측하는 대상이고, **누가 부르든** 계측된다. Extension 클래스로더로 로드됐다고 빠지지 않는다(에이전트의 무시 목록은 "그 로더가 만든 클래스를 바꾸지 않는다" 는 뜻이지, 그 클래스가 부르는 JDK 호출까지 빼 주는 게 아니다). 그대로 두면 이런 일이 생긴다 :

```
25초마다  [CLIENT] GET /agent/commands   ← 부모 없는 루트 스팬, 네 서비스에서 계속
```

Collector → Kafka → ClickHouse 까지 흘러가 `spans` 에 쌓이고, `collector:8081` 이 Service map 에 외부 노드로 그려진다. 헬스체크(`#92`)와 같은 종류의 오염이다.

끄는 공식 방법이 있다 : `InstrumentationUtil.suppressInstrumentation { httpClient.send(...) }`. 에이전트가 자기 exporter 의 HTTP 호출을 계측하지 않으려고 만든 스위치로, 이 블록 안의 호출은 스팬을 만들지 않는다. 1.63 부터 public(`io.opentelemetry.api.impl`)이 됐다. 다만 Extension 안의 `io.opentelemetry.*` 참조를 에이전트가 자기 것으로 바꿔 끼우는(remap) 범위에 이 패키지가 드는지는 조사가 확인하지 못했다. **첫 검증에서 꼭 볼 것** : 롱폴링을 돌린 뒤 ClickHouse `spans` 에 `GET /agent/commands` 가 0건인지.

쓰면 안 되는 방법 : `otel.instrumentation.java-http-client.enabled=false`. 쇼핑몰 앱의 HTTP 호출 계측까지 꺼져 `order → payment` 화살표가 사라진다.

### 2.5 덤프 자체 : `ThreadMXBean.dumpAllThreads`

JVM 에 기본으로 든 관리 API 다. 호출하면 모든 스레드의 이름 · 상태 · 스택 · 쥐고 있는 락을 돌려준다. 알아 둘 것 :

| 무엇 | 실제 |
|---|---|
| 비용 | HotSpot 은 이걸 VM 작업으로 돌려 **모든 자바 스레드를 잠깐 멈춘다**(safepoint). 몇 ms 인지는 공식 수치가 없어 우리가 재야 한다. 사람이 버튼을 누를 때만 뜨므로 문제는 아니지만, 같은 에이전트에 짧은 간격으로 두 번 오면 한 번만 뜨는 보호가 있으면 좋다 |
| 인자 | `dumpAllThreads(lockedMonitors, lockedSynchronizers, maxDepth)`. 락 정보 둘은 `isObjectMonitorUsageSupported()` 등으로 지원 여부를 먼저 본다. Java 10+ 는 `maxDepth` 로 스택 줄 수를 제한할 수 있다 |
| 포맷 | `ThreadInfo.toString()` 은 스택을 **8줄에서 자른다**. jstack 처럼 전부 보려면 우리가 직접 글로 만든다(이름 · 상태 줄, `at …` 줄, `- locked <…>`, 기다리는 락) |
| 크기 | 스레드 300개 × 50줄 × 약 100바이트 ≈ 1~2 MB 가 상한이고 보통 수백 KB(추정). 압축 없이 시작하고 Collector 쪽 요청 크기 상한만 정한다 |

### 2.6 롱폴링 양쪽

```
Extension (데몬 스레드 1개)                       Collector (Spring MVC, 8081)
  loop {
    GET /agent/commands?service=…&instance=…  ──▶  DeferredResult 로 최대 25초 붙잡음
       (요청 타임아웃 30초 > 25초)                    · 명령이 오면 setResult(200 + 명령)
    ◀── 200 {command_id, type, reply_to}  또는      · 25초 지나면 204 (명시해야 한다. 기본은 503)
    ◀── 204 빈 응답                                  · 폴링 맵 (service, instance) → DeferredResult
    200 이면 덤프 → POST reply_to/agent/commands/{id}/result
    204 면 바로 다시 GET
    연결 실패 · 5xx 면 1 → 2 → 4 … 60초 백오프 + 무작위, 성공하면 초기화
  }
```

- Collector 쪽 `DeferredResult` 는 요청을 붙잡아 두되 **서블릿 스레드는 풀어 준다**. 타임아웃 기본 동작이 503 이라, 합의안의 "내 에이전트 아님 = 503" 과 겹치지 않게 **25초 타임아웃에 204 를 명시**한다
- Collector 가 재시작되면 Extension 의 다음 GET 이 새 연결로 나가므로 따로 재연결 코드는 없다. 백오프만 있으면 된다
- 보유 판정(합의안 1)은 "지금 이 에이전트의 `DeferredResult` 를 들고 있나" 다. 맵에서 빼는 시점(`onCompletion` · `onTimeout`)을 놓치면 새는 것이니 조심
- 재연결 빈틈(합의안 3) : 폴링이 없는 찰나에 명령이 오면 에이전트별로 `timeout_ms` 동안 보관했다가 다음 GET 에 준다

### 2.7 남(Pinpoint · OTel)은 어떻게 하나

- **Pinpoint** : 에이전트가 Collector 와 gRPC 양방향 스트림을 계속 열어 두고(`HandleCommandV2`), 결과는 별도 호출로 올린다. 화면 → Collector 는 Redis pub/sub 이고, "내 에이전트 아님" 인 Collector 는 **조용히 무시**하며 화면이 타임아웃까지 기다린다. 우리는 그 구조를 HTTP 팬아웃 + 503 으로 옮긴 변형이다(ADR `#31`)
- **OTel** : 온디맨드 스레드 덤프나 "에이전트 원격 명령" 표준은 없다. 프로파일(profiles) 신호는 2026-03 알파고 Java 에이전트에 내장 스위치가 없다. 원격 관리 프로토콜 OpAMP 는 Beta 지만 양방향 custom message 가 아직 개발 단계고 Java 에이전트에 안 들어 있다. 노션 정리의 "기능 하나에 비해 무겁다" 가 맞다

### 2.8 데이터는 어디로 흐르나

```
(평소)   Extension ──GET /agent/commands──▶ Collector [25초 붙잡음 → 204] ──▶ 다시 GET   ← 계속, 스팬 0건이어야 함

(누를 때)
화면 ──▶ API server ──POST /internal/thread-dump (팬아웃)──▶ Collector A · B · C
                                                   A : 폴링 쥐고 있음 → 붙잡은 GET 에 200 {command_id, reply_to=A 주소}
                                                   B · C : 없음 → 503
Extension ──ThreadMXBean 덤프──▶ POST A주소/agent/commands/{id}/result (토큰)
Collector A ──▶ 기다리던 팬아웃 요청에 덤프 반환 ──▶ API server ──▶ (CH thread_dumps, Q16) ──▶ 화면
```

| 질문 | 답 |
|---|---|
| 이번 이슈가 만드는 구간 | Extension 전부 + Collector 의 `/agent/**` 문 둘 + 시험용으로 명령을 넣는 문 |
| 이번 이슈가 안 만드는 구간 | API server 팬아웃(재범), 화면, CH 저장 |
| 첫 검증의 끝 | Collector 에 명령을 넣으면 25초 안에 덤프 본문이 Collector 로그(또는 임시 보관)에 도착한다 |
| 실패하면 어디에 남나 | 지금 설계에는 없다. 결과 POST 가 실패하면 Extension 로그뿐 (5 절에서 정할 것) |

### 2.9 자주 헷갈리는 것

| 헷갈리는 것 | 실제 |
|---|---|
| Extension 은 에이전트를 다시 빌드하는 것이다 | 아니다. 옆에 두는 jar 를 `-Dotel.javaagent.extensions` 로 가리키기만 한다 |
| Extension 코드는 앱 코드다 | 아니다. 쇼핑몰 앱은 모른다(ADR `#33` 비침습 유지). 이미지와 `ENTRYPOINT` 만 바뀐다 |
| Extension 안에서 보낸 HTTP 는 에이전트가 안 본다 | 본다. `suppressInstrumentation` 으로 감싸야 한다 |
| 롱폴링은 연결을 계속 열어 두는 것이다 | 요청 하나가 25초까지 붙잡혔다 끝나고 다시 보내는 것이다. 웹소켓처럼 하나가 계속 열려 있지 않다 |
| Collector 가 응답 없이 25초 지나면 타임아웃 에러다 | 그게 정상 경로(204 "명령 없음")다. 에러로 안 보이게 204 를 명시해야 한다 |
| 덤프는 공짜다 | 모든 스레드를 잠깐 멈춘다. 버튼 누를 때만, 짧은 간격 중복은 막는다 |
| `ThreadInfo.toString()` 이면 jstack 과 같다 | 스택 8줄에서 잘린다. 직접 글로 만든다 |
| Collector 주소는 `collector:4317` 이다 | 그건 gRPC(OTLP) 포트다. 명령은 Spring MVC 쪽 `collector:8081` 이다 |

---

## 3. 1차 질문 (사람 차례)

- **Q. 2 절이 너무 어렵다. 다 알아야 하나**
  - A. 아니다. 결정에 필요한 것은 셋 : ① Extension 은 에이전트 옆 jar 이고 데몬 스레드 하나로 Collector 에 "명령 있어?" 를 계속 묻는다 ② 그 HTTP 도 트레이스로 잡히니 `suppressInstrumentation` 으로 감싸고 ClickHouse 에 0건인지 본다 ③ Collector 의 25초 대기는 끝날 때 204 로 답해야 한다(기본 503 이 "내 에이전트 아님" 과 겹친다). 나머지(설정 키 · 덤프 포맷 · 백오프 숫자 · Pinpoint 비교)는 구현 세부거나 면접 재료다.
- **Q. 데몬 스레드가 뭔가**
  - A. "주인이 퇴근하면 같이 퇴근하는" 보조 일꾼 스레드다. 자바는 일반 스레드가 하나라도 남아 있으면 프로그램을 끝내지 않는데, 데몬 스레드는 기다려 주지 않는다. Extension 의 "명령 있어?" 반복 스레드가 일반 스레드면 쇼핑몰을 끄려 해도 안 꺼진다. 그래서 데몬으로 만든다.
- **Q. Extension 이 에이전트에게 요청하나, Collector 에게 요청하나**
  - A. Collector 에게 보낸다. Extension 은 에이전트 **안에** 들어가 사는 부품이라 에이전트에게 요청할 일이 없다. 에이전트가 스팬을 Collector 4317(gRPC)로 보내듯, Extension 은 Collector 8081(HTTP)로 "명령 있어?" 를 묻는다. 같은 JVM 에서 나가는 두 갈래 연결이다.
- **Q. `suppressInstrumentation` 으로 감싸고 ClickHouse 에 0건인지 본다는 말은**
  - A. 에이전트는 JVM 안에서 나가는 HTTP 를 전부 기록한다. Extension 의 "명령 있어?" 도 예외가 아니라 25초마다 가짜 스팬이 생겨 Collector → ClickHouse 까지 간다. `suppressInstrumentation { … }` 은 "이 블록 안의 호출은 기록하지 마" 라는 표시다. 첫 검증 때 ClickHouse `spans` 에서 `/agent/commands` 를 찾아 0건이면 표시가 먹힌 것이다.
- **Q. Collector 쪽 25초 대기는 어떤 대기인가**
  - A. Extension 이 "명령 있어?" 를 물으면 Collector 는 바로 "없어" 하지 않고 **답을 25초까지 미뤄 둔다.** 그 사이 화면에서 버튼이 눌리면 미뤄 둔 요청에 "덤프 떠" 로 즉시 답한다. 25초 동안 아무 일도 없으면 "없어(204)" 로 답하고, Extension 은 곧바로 다시 묻는다. 1초마다 묻는 것보다 요청 수는 25배 적고, 명령은 기다림 없이 바로 간다(롱폴링).

- **Q. (내 정리) Extension 은 자바 데몬 스레드를 쓰고, 그 스레드가 25초마다 Collector 에 "덤프 명령 들어왔나" 를 쏘고, 25초 안에 응답이 오면 바로 덤프를 뜨는 건가**
  - A. 거의 맞다. 하나만 고치면 "25초마다 쏜다" 가 아니라 **"쏘고, 답이 올 때까지 최대 25초 기다리고, 답이 오면 곧바로 다시 쏜다"** 다. 쉬는 시간이 없어서 늘 요청 하나가 Collector 에 걸려 있다. 그래서 버튼을 누르면 25초를 기다리지 않고 즉시 명령이 간다.
- **Q. Extension 쪽 요청에 붙이는 특별한 표시가 뭔가**
  - A. 두 가지가 다르다. `suppressInstrumentation` 은 요청에 붙는 게 아니라 **우리 JVM 안의 에이전트에게** "이 호출은 기록하지 마" 라고 하는 코드 감싸기다. 네트워크로는 아무것도 안 나간다. 요청에 실제로 붙는 것은 인증용 **토큰 헤더**(`MONIMO_AGENT_TOKEN`, 합의안 4)로, Collector 가 "진짜 우리 에이전트구나" 를 확인한다.
- **Q. 롱폴링은 무조건 Collector 컨테이너 하나 쪽인가. 팬아웃할 때 컨테이너 하나에 Extension 하나가 붙어 있나**
  - A. 요청 하나는 늘 Collector **한 대**에 붙는다. 하지만 1:1 은 아니다. ① Collector 한 대는 **여러** Extension 의 요청을 동시에 들고 있다(쇼핑몰 4개 × 파드 수). ② 어느 Collector 에 붙을지는 K8s Service 가 요청마다 고르므로 다음 요청은 다른 Collector 로 갈 수 있다. 그래서 API server 는 지금 누가 그 에이전트 요청을 들고 있는지 몰라 **전부에게** 묻고(팬아웃), 들고 있는 한 대만 200, 나머지는 503 으로 답한다(합의안 1). 로컬 compose 는 Collector 가 한 대라 늘 그 한 대다.

- **Q. `suppressInstrumentation` 이 "JVM 안의 에이전트에게 하는 말" 이란 게 무슨 뜻인가**
  - A. 에이전트는 쇼핑몰 JVM 안에 같이 살면서 나가는 HTTP 호출을 옆에서 받아 적는 기록원이다. `suppressInstrumentation { 호출 }` 은 그 기록원에게 "이 블록 안의 호출은 적지 마" 라고 메모를 붙이는 것이다. 메모는 JVM 안에서만 쓰이고 요청 내용(주소 · 헤더 · 본문)은 하나도 안 바뀐다. Collector 는 이 표시를 볼 수 없다.
- **Q. 토큰 헤더는 HTTP 요청 헤더에 붙나**
  - A. 그렇다. `X-Monimo-Agent-Token: <값>` 같은 헤더로 매 요청에 붙고, Collector 가 값을 비교해 틀리면 401 로 거절한다. 값은 환경변수로 넣는다.
- **Q. Collector 가 여러 대인데 "요청 하나" 가 이해가 안 된다**
  - A. 롱폴링 요청은 전화 한 통과 같다. 전화 한 통은 상담원 한 명과만 연결된다. 콜센터(K8s Service)에 걸면 상담원(Collector) 중 한 명에게 돌려지고, 그 통화가 끝나면 Extension 이 다시 걸 때 다른 상담원에게 갈 수 있다. 지금 이 순간 그 Extension 과 통화 중인 Collector 는 딱 한 대지만, 누구인지는 시간마다 바뀐다. 그래서 API server 는 "주문-파드1 이랑 통화 중인 사람?" 을 모든 Collector 에게 묻는다.

- **Q. `suppressInstrumentation` 은 Extension 안에 있고, 그게 붙은 요청은 에이전트가 안 읽는 건가**
  - A. Extension 코드 안에 있는 건 맞다. "안 읽는다" 보다 **"기록(스팬)을 안 남긴다"** 가 정확하다. 요청은 그대로 Collector 로 나가고, 에이전트는 그 호출을 보고도 스팬을 만들지 않는다.
- **Q. Extension 은 하나고 Collector 가 여러 개인가. 그 Extension 과 연결된 Collector 에서 덤프가 일어나나**
  - A. 둘 다 고친다. Extension 은 **쇼핑몰 JVM 마다 하나**라 여러 개다(서비스 4개 × 파드 수, 로컬은 4개). 덤프는 Collector 가 아니라 **쇼핑몰 JVM 안에서 Extension 이** 뜬다(그 JVM 의 스레드를 찍는 것이니까). 연결된 Collector 는 "덤프 떠" 명령을 전해 주고 결과를 받아 API server 에 넘기는 중계만 한다.

- **Q. 쇼핑몰 컨테이너마다 Extension 이 연결된 건가. Extension(스레드)을 Collector 로 쏘는 건가**
  - A. "연결" 이 아니라 **안에 들어 있다**. 컨테이너 하나 = JVM 하나 = 에이전트 하나 = Extension 하나이고, Extension 은 이미지 안의 jar 파일이라 그 JVM 안에서 같이 돈다. 그리고 Extension 이나 스레드가 Collector 로 가는 게 아니다. Extension 이 띄운 데몬 스레드가 그 자리에서 **HTTP 요청("명령 있어?")** 을 Collector 로 보낸다. 스레드는 일꾼이고, 날아가는 건 요청이다.

---

## 4. 1차 정리

3 의 질문 열네 개를 거친 뒤 확실해진 것만 묶는다.

- 확실한 것 :
  - Extension 은 쇼핑몰 컨테이너(JVM)마다 하나씩 **안에** 들어 있는 jar 다. 이미지와 `ENTRYPOINT` 한 줄만 바뀌고 앱 코드는 그대로다
  - Extension 이 띄운 데몬 스레드(일꾼) 하나가 Collector 8081 에 "명령 있어?" 를 HTTP 로 묻고, 답을 받으면 곧바로 다시 묻는다. Collector 는 답을 최대 25초 미뤄 두고, 끝날 때 204 로 답한다
  - "덤프 떠" 가 오면 일꾼이 **자기 JVM** 의 덤프를 떠서 HTTP 로 보낸다. Collector 는 중계만 한다
  - 일꾼의 HTTP 호출은 `suppressInstrumentation` 으로 감싸 스팬이 안 남게 하고, 토큰은 HTTP 헤더로 붙인다
  - Collector 가 여러 대면 그 순간 통화 중인 Collector 는 한 대이고 누구인지 바뀐다. 그래서 API server 가 전부에게 묻는다(팬아웃)
- 아직 모르는 것 (5 에서 확인) :
  - `suppressInstrumentation` 이 Extension 안에서 정말 먹히나 (조사 미확인)
  - `afterAgent` 가 정말 앱보다 먼저 불리나, 식별 값(`service.name` · `service.instance.id`)을 어디서 읽나
  - 덤프가 몇 ms 걸리고 얼마나 큰가
  - 무엇을 고를지 : 언어, 덤프 포맷, 결과 실패 처리, 명령 · 결과 모양, Collector 에 명령 넣는 문, 중복 덤프 보호

---

## 5. 선택지와 설명

### 조사 프롬프트

원문은 같은 폴더의 [`prompts.md`](prompts.md) 에.

### 우리 데이터로 확인한 것 (먼저 본다)

가장 불확실한 셋을 작은 시험 Extension(Java, 클래스 2개)으로 직접 확인했다. 레포 브랜치는 건드리지 않았다. `monimo/shop-payment:dev` 이미지에 `-Dotel.javaagent.extensions` 로 얹고, Collector 대신 아무 요청이나 404 로 받는 작은 HTTP 서버를 두고, 스팬은 화면 출력(`logging-otlp`)으로 읽었다.

| 확인 | 값 |
|---|---|
| `afterAgent` 가 불리는 때 | `thread=main`, 로그 3번째 줄. `Starting PaymentApplication` 은 14번째 줄. **앱보다 먼저, main 스레드에서** (그래서 여기서 기다리면 기동이 늦어진다) |
| 식별 값 읽기 | `AutoConfiguredOpenTelemetrySdk.getResource()` 는 **public 이 아니라 쓸 수 없었다**(javap 확인). 대신 `AutoConfigurationCustomizerProvider` 의 `addResourceCustomizer` 로 Resource 를 받아 `service=shop-payment instance=shop-payment-local-1` 을 읽었다 (`#30` 이름표 그대로) |
| 설정 읽기 | 같은 자리에서 `ConfigProperties` 로 `otel.monimo.collector.url` 을 읽음 |
| `suppressInstrumentation` 없이 보낸 요청 2건 | 스팬 **2개** (`url.full = …/plain-0`, `/plain-1`). 걱정대로 Extension 호출도 기록된다 |
| `suppressInstrumentation` 으로 감싼 요청 2건 | 스팬 **0개**. HTTP 서버는 4건 모두 받았다(`/suppressed-0`, `/suppressed-1` 포함). **요청은 나가고 기록만 안 남는다** |
| 덤프 시간 | 스레드 29~30개, 스택 240~248줄, **14.7 ~ 15.7 ms** (락 정보 포함) |
| 덤프 크기 (`ThreadInfo.toString()`) | 약 **20 KB**. 스레드마다 스택이 8줄에서 잘린 상태 |

`suppressInstrumentation` 의 remap 문제(조사가 확인 못 한 것)는 이 시험으로 닫혔다.

### 나온 선택지

정할 것이 여섯 가지이다.

#### ⓐ Extension 을 무슨 언어로

| 방법 | 얻는 것 | 포기하는 것 |
|---|---|---|
| **a1. Java** | 런타임 의존성 0개, jar 가 수십 KB. 시험 Extension 이 이미 Java 로 돌았다. 공식 예제도 Java | 레포에서 이 모듈만 Java (나머지는 Kotlin) |
| a2. Kotlin | 레포 언어 통일 | `kotlin-stdlib`(약 1.7 MB)를 jar 에 넣어야 하고, 앱의 Kotlin 과 겹치지 않게 shadow 플러그인으로 이름을 바꿔 묶어야 한다(relocate). 에이전트 Extension 로더에서 잘 도는지 시험 안 함 |

합의안의 "Extension 의존성 0개" 를 그대로 지키는 것은 a1 이다. a2 는 stdlib 하나가 들어가는 순간 "클래스로더 충돌 위험이 없다" 는 롱폴링 선택 이유를 스스로 약하게 한다.

#### ⓑ 덤프를 어떤 모양으로

| 방법 | 크기 (시험 기준) | 사람이 읽기 | 구현 |
|---|---|---|---|
| b1. `ThreadInfo.toString()` 그대로 | 20 KB | 스택이 **8줄에서 잘린다**. 깊은 호출(Spring · JDBC)에서 정작 우리 코드 줄이 안 보일 수 있다 | 한 줄 |
| **b2. jstack 비슷한 글로 직접 만들기** (잘림 없음, `maxDepth` 상한 예 : 64) | 수십 ~ 수백 KB (추정) | 개발자가 익숙한 모양 그대로 | 30줄 안팎 |
| b3. JSON 구조로 (스레드별 이름 · 상태 · 프레임 배열) | b2 보다 큼 | 화면이 상태별로 묶어 보여 주기 좋다 | 중간. 화면 · CH 저장 모양과 같이 정해야 한다 |

b3 는 화면 · `thread_dumps` 표(Q16)와 묶여 있어 이번 이슈 혼자 정할 수 없다. b2 로 글을 보내고, 화면이 구조가 필요해지면 그때 바꾸는 순서가 무난하다.

#### ⓒ 결과 보내기가 실패하면

| 방법 | 얻는 것 | 포기하는 것 |
|---|---|---|
| c1. 로그만 남기고 버린다 | 가장 단순. 사람이 다시 누르면 된다 | 화면은 타임아웃까지 기다렸다 실패를 본다 |
| **c2. 회신 주소로 짧게 몇 번 다시 보낸다** (예 : 1초 간격 3회, 명령의 `timeout_ms` 안에서) | 일시적 끊김을 넘긴다 | 3회 뒤엔 결국 버린다 |
| c3. 회신 주소가 안 되면 Service 주소로 보낸다 | Collector A 가 죽어도 다른 Collector 에 닿는다 | 합의안 2 와 어긋난다 : 다른 Collector 는 그 명령을 기다리는 요청이 없어 받아도 넘길 곳이 없다 |

#### ⓓ 명령 · 결과의 모양 (제안)

```
GET  /agent/commands?service=shop-order&instance=shop-order-local-1     헤더 X-Monimo-Agent-Token
  204                         ← 25초 동안 명령 없음
  200 {"command_id":"c-7f3a…","type":"THREAD_DUMP","reply_to":"http://collector-0.collector:8081","timeout_ms":10000}

POST {reply_to}/agent/commands/{command_id}/result                      헤더 같은 토큰
  {"service":"shop-order","instance":"shop-order-local-1","taken_at":"2026-10-08T…Z",
   "elapsed_ms":15,"thread_count":30,"format":"jstack","dump":"\"main\" #1 …"}
  200 받음  ·  404 그 명령은 이미 끝났거나 모름  ·  401 토큰 틀림
```

`type` 이 `THREAD_DUMP` 가 아니면 Extension 은 무시한다(합의안 4). 사람이 읽기 쉬운 이름(snake_case)은 backend API 명세 관례에 맞췄다.

#### ⓔ Collector 에 명령을 어떻게 넣어 시험하나 (API server 팬아웃 전)

| 방법 | 얻는 것 | 포기하는 것 |
|---|---|---|
| **e1. Collector 에 진짜 팬아웃 받는 문 `POST /internal/thread-dump` 를 이번에 만든다** (`X-Internal-Token`, 노션 흐름 ②③ 그대로) | 재범이 API server 쪽을 만들 때 Collector 쪽이 이미 있다. curl 로 시험하면 그게 곧 진짜 경로 | Collector 일이 조금 늘어난다 |
| e2. 시험용 임시 문 | 빠르다 | 나중에 지워야 하고, 진짜 경로를 한 번 더 시험해야 한다 |
| e3. Collector 단위 테스트로만 | 문이 늘지 않는다 | Extension 과 이어 붙인 E2E 시험이 없다. ADR `#33` 되돌림 (a) "동작한다" 를 말할 근거가 약하다 |

#### ⓕ 같은 에이전트에 덤프가 연달아 오면

| 방법 | 얻는 것 | 포기하는 것 |
|---|---|---|
| f1. 막지 않는다 | 단순 | 버튼을 연타하면 그만큼 JVM 이 15 ms 씩 멈춘다 |
| **f2. Extension 이 직전 덤프에서 N초(예 : 5초) 안이면 직전 결과를 다시 보낸다** | 연타해도 한 번만 멈춘다 | 5초 안의 두 번째 결과가 같은 내용이다 |

### 이번 이슈와 backend 의 나눔

| 쪽 | 무엇 | 어디 |
|---|---|---|
| 쇼핑몰 `#34` | Extension(Java), Dockerfile 4개 · compose env, README, 이 폴더 | 이 레포 |
| backend (새 이슈) | Collector `GET /agent/commands` · `POST /agent/commands/{id}/result` · (e1 이면) `POST /internal/thread-dump`, 토큰 검사, 보유 판정 맵, 빈틈 보관, 204 명시, 테스트 | `monimo-backend/collector` |
| E2E | 두 레포를 같이 띄워 curl 로 `/internal/thread-dump` → 덤프가 돌아오는지, ClickHouse 에 `/agent/commands` 스팬 0건인지 | 두 PR 에 같은 결과를 적는다 |

### 남이 어떻게 하나

| 도구 · 제품 | 어떻게 하나 | 출처 |
|---|---|---|
| Pinpoint | 에이전트 ↔ Collector gRPC 양방향 스트림(`HandleCommandV2`), 결과는 별도 호출. 화면 → Collector 는 Redis pub/sub, "내 에이전트 아님" 은 조용히 무시 | https://github.com/pinpoint-apm/pinpoint-grpc-idl/blob/master/proto/v1/Service.proto , https://pinpoint-apm.gitbook.io/pinpoint/v2.5.3/documents/realtime |
| OTel Java Agent 공식 예제 | Extension = Java, 의존성은 `compileOnly`, shadow 로 jar 하나 | https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/v2.31.1/examples/extension |
| OTel 에이전트 자신 | exporter 의 HTTP 호출을 `InstrumentationUtil.suppressInstrumentation` 으로 감싸 자기 자신을 기록하지 않는다 | https://github.com/open-telemetry/opentelemetry-java/pull/8413 |
| OpAMP | 원격 관리 표준(Beta). 양방향 custom message 는 개발 단계, Java 에이전트에 미내장 | https://opentelemetry.io/docs/specs/opamp/ |

### AI 가 틀렸거나 덜 맞았던 것 (남겨 둔다)

- 조사 질문에서 "Extension 클래스로더면 계측 안 되지 않나" 라고 기대했다. 틀렸다. 시험으로 스팬 2개를 봤다
- 2 절에 "Extension 은 `ConfigProperties` 로 설정을 읽는다" 까지만 썼는데, 식별 값을 `AutoConfiguredOpenTelemetrySdk` 에서 꺼낼 수 있다고 막연히 생각했다. `getResource()` 가 public 이 아니어서 `addResourceCustomizer` 로 돌아가야 했다(javap 로 확인)
- 2 절의 "덤프 크기 수백 KB" 는 추정이었다. `toString()` 기준 20 KB(스레드 30개)였다. 잘리지 않은 b2 모양의 크기는 아직 안 쟀다

### 라이브러리 · 레포를 직접 열어 확인한 것

| 확인 | 결과 |
|---|---|
| `opentelemetry-api-1.65.0.jar` | `io/opentelemetry/api/impl/InstrumentationUtil` 있음, `suppressInstrumentation(Runnable)` public |
| `opentelemetry-sdk-extension-autoconfigure-1.65.0.jar` | `AutoConfiguredOpenTelemetrySdk.getResource()` · `getConfig()` 는 package-private |
| `opentelemetry-javaagent-extension-api-2.31.1-alpha.jar` | `AgentListener.afterAgent(AutoConfiguredOpenTelemetrySdk)` |
| backend `api-server/.../ErrorCode.kt` | `AGENT_NOT_REACHABLE`(503) · `THREAD_DUMP_TIMEOUT`(503) 이미 있음 |
| backend `collector/` | Spring MVC 8081, `/agent/**` · `/internal/**` 문 없음, 토큰 설정 없음 |
| backend `api-server/.../threaddump/` | `.gitkeep` 만 (재범 담당 자리) |

### 확인 못 한 것

- Kotlin(a2)으로 만든 Extension 이 shadow + relocate 로 에이전트 로더에서 잘 도는지
- 잘리지 않은 jstack 모양(b2)의 실제 크기 · 만드는 시간
- 쇼핑몰 네 서비스가 같이 돌 때(톰캣 스레드 200개 이상) 덤프 시간. 시험은 결제 서비스 혼자 · 스레드 30개였다
- Collector 쪽 `DeferredResult` 25초 · 204 · 보유 맵이 실제로 새지 않는지 (backend 이슈에서)
- Collector 를 재시작했을 때 Extension 이 백오프 뒤 다시 붙는지 (구현 뒤 E2E 시험에서)

---

## 6. 2차 질문 (사람 차례)

- **Q. "Extension 은 앱보다 먼저, main 스레드에서 뜬다. 그래서 일꾼만 띄우고 바로 돌아와야 한다" 를 설명해 달라**
  - A. `java -javaagent:… -jar app.jar` 를 실행하면 JVM 은 main 스레드 하나로 ① 에이전트 설치 → ② Extension 의 `afterAgent` 호출 → ③ 앱 `main()`(Spring 기동) 을 **차례로** 한다. ② 가 끝나야 ③ 이 시작된다. 그래서 `afterAgent` 안에서 Collector 응답을 기다리면(롱폴링 25초, Collector 가 꺼져 있으면 재시도) 그동안 쇼핑몰이 안 뜬다. 헬스체크가 실패하고 컨테이너가 재시작될 수 있다. 그래서 `afterAgent` 에서는 데몬 스레드(일꾼)만 만들어 놓고 즉시 return 한다. 그러면 main 은 바로 ③ 으로 가고, 일꾼은 옆에서 따로 묻기를 시작한다. 시험에서 `afterAgent` 로그가 3번째 줄, `Starting PaymentApplication` 이 14번째 줄이었다.
- **Q. 정할 것들의 장단점** → 5 절 표에 있다. 대화 답에 항목별로 다시 풀었다
- **Q. 예시 프롬프트의 "골라야 할 곳" 네 가지(스택 상한 · 연타 보호 · 토큰 헤더 이름 · 확인할 것)는 무슨 뜻인가**
  - A. 스택 상한은 스레드 하나의 스택을 몇 줄까지 담나(Spring · 톰캣 요청 스레드는 보통 60~120줄, 원인은 위쪽). 연타 보호는 같은 쇼핑몰에 N초 안에 명령이 또 오면 새로 안 뜨고 직전 결과를 재사용(덤프마다 JVM 이 잠깐 멈추므로). 토큰 헤더 이름은 합의안 4(재범 담당)와 맞출 이름. 확인할 것은 완료 기준 : `#32` 회고에서 "결정 프롬프트에 완료 기준이 없었다" 가 나와 넣었다. 승조 결정 : 상한 128, 연타 10초, 헤더 `X-Monimo-Agent-Token`, 확인할 것 넣기

---

## 7. 2차 정리

- 좁혀진 선택지 : ⓐ Java · ⓑ jstack 모양 직접 만들기(상한 128줄) · ⓒ 회신 주소로 1초 × 3회 · ⓓ 제안안 · ⓔ Collector `POST /internal/thread-dump` 까지 이번에 · ⓕ 10초 안이면 직전 결과 재사용
- 결정을 가르는 기준 : 합의안 "Extension 의존성 0개" 를 지키나, 쇼핑몰 기동을 늦추지 않나, 시험한 경로가 진짜 경로인가, 덤프가 JVM 을 몇 번 멈추나
- 무엇을 정해야 하나 : 위 여섯과 헤더 이름 · 완료 기준(8 절에서 승조가 정함)

### 여기서 나온 면접 질문

1. 에이전트 코드를 안 고치고 스레드 덤프 기능을 어떻게 넣었나. Extension 은 어디서 시작되고 왜 그 자리에서 바로 돌아와야 하나
2. 에이전트가 자기 자신의 HTTP 호출을 트레이스로 남기는 문제는 어떻게 막았고 어떻게 확인했나
3. Collector 가 여러 대일 때 명령이 올바른 에이전트에 닿는 원리는. 결과는 왜 명령을 준 Collector 로 보내나
4. 롱폴링 대기가 끝날 때 왜 204 를 명시했나
5. 덤프를 뜨면 앱에 어떤 비용이 있고 어떻게 줄였나
6. Extension 을 왜 Kotlin 이 아니라 Java 로 만들었나

---

## 8. 구현 프롬프트 (사람 차례)

> 승조가 7 을 읽고 쓴 결정 원문이다. AI 가 추천안으로 초안을 썼고, 승조가 스택 상한(64 → 128) · 연타 보호(5 → 10초)를 바꾸고 헤더 이름 · 확인할 것을 확정했다. 같은 원문이 [`prompts.md`](prompts.md) 에 있다.

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
