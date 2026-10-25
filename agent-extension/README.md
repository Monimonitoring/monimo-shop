# agent-extension

OTel Java Agent 에 얹는 스레드 덤프 명령 수신 Extension. 우리가 만드는 유일한 에이전트 코드다 (ADR #33).
쇼핑몰 앱 코드는 이걸 모른다. 이미지에 jar 하나(`/app/otel/agent-extension.jar`)를 넣고 `ENTRYPOINT` 에 `-Dotel.javaagent.extensions=` 한 줄을 더했을 뿐이다.

설계 과정은 [`docs/seungjo/34-thread-dump-extension/`](../docs/seungjo/34-thread-dump-extension/README.md).

## 하는 일

```
쇼핑몰 JVM
 └─ 에이전트 + Extension
      afterAgent() : 데몬 스레드 monimo-command-poller 하나만 띄우고 바로 돌아온다 (앱 main 보다 먼저 불리기 때문)
      일꾼 스레드   : GET  {Collector}/agent/commands?service=…&instance=…   (Collector 가 최대 25초 붙잡는다)
                     ← 204 : 명령 없음, 곧바로 다시 묻는다
                     ← 200 THREAD_DUMP : 이 JVM 의 덤프 → POST {reply_to}/agent/commands/{id}/result
```

- **Java 로 쓴다.** 런타임 의존성 0개, jar 약 19 KB. Kotlin 이면 kotlin-stdlib 를 넣고 이름을 바꿔 묶어야 한다. 테스트만 Kotest(Kotlin)
- **모든 HTTP 호출을 `InstrumentationUtil.suppressInstrumentation` 으로 감싼다.** 안 감싸면 에이전트가 이 호출도 스팬으로 남겨 25초마다 가짜 트레이스가 쌓인다
- 식별 값(`service.name` · `service.instance.id`)은 에이전트가 만든 Resource 에서 읽는다 (`ConfigCapture`). 백엔드 `agent_id` 와 같은 값이다
- `THREAD_DUMP` 말고는 무시한다. 덤프는 jstack 모양, 스레드마다 스택 128줄까지. 10초 안에 또 오면 직전 결과를 다시 준다(덤프마다 JVM 이 잠깐 멈추므로)
- 결과 보내기가 실패하면 1초 간격 3번까지, 404(이미 끝난 명령)면 그만. Collector 에 안 닿으면 1 → 60초 백오프 + 무작위

## 설정

에이전트 설정 체계를 그대로 탄다 (`-D` > 환경변수 > `otel/agent.properties`).

| 키 (환경변수) | 기본값 | 설명 |
|---|---|---|
| `otel.monimo.agent.token` (`OTEL_MONIMO_AGENT_TOKEN`) | 없음 | Collector `MONIMO_AGENT_TOKEN` 과 같은 값. **비우면 Extension 이 아무것도 안 한다** |
| `otel.monimo.collector.url` (`OTEL_MONIMO_COLLECTOR_URL`) | `http://collector:8081` | Collector HTTP 주소 (OTLP 4317 이 아니라 Spring MVC 쪽) |

## Collector 쪽

`monimo-backend` Collector 의 문 셋 (backend `#122`) : `GET /agent/commands` · `POST /agent/commands/{id}/result` · `POST /internal/thread-dump`.
덤프를 손으로 떠 보려면 (backend Collector 가 떠 있을 때) :

```bash
docker run --rm --network monimo-dev curlimages/curl:8.10.1 -s -X POST http://collector:8081/internal/thread-dump \
  -H 'Content-Type: application/json' -H 'X-Internal-Token: local-internal-token' \
  -d '{"service":"shop-order","instance":"shop-order-local-1","timeout_ms":10000}'
```
