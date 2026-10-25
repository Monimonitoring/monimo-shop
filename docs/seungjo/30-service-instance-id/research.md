# 리서치 : 재시작해도 그대로인 에이전트 식별자 (`service.instance.id`)

> 용어만 glossary(`monimo-backend/docs/design/glossary.md`) 기준으로 바꿈 (`#240`) : 원문 인용도 용어만 바꾸고 문장은 그대로 뒀다

- 날짜 2026-10-06 / 관련 이슈 `#30`
- 쓴 도구 : 웹 리서치 서브에이전트 1개 · 로컬 ClickHouse 조회 · 에이전트 jar 직접 열어 확인

> **읽는 순서가 곧 쓰는 순서다.** 1 → 2 를 쓰고 멈춰서 승조가 3 을 묻는다. 답하고 4 를 쓴다.
> 5 를 쓰고 멈춰서 승조가 6 을 묻는다. 답하고 7 을 쓴다. 8 은 승조가 쓴다.
> **3 · 6 · 8 은 사람 차례다.** 비어 있으면 아직 거기까지 온 것이고, 대신 채우지 않는다.

---

## 1. 이번 작업은 무엇을 하는 일인가

- **지금 무엇이 잘못됐나** : 쇼핑몰 서비스를 재시작하면 화면에서 옛 에이전트가 사라지고 새 에이전트가 붙은 것처럼 보인다.
- **그래서 무엇을 만드나** : 에이전트 이름표를 재시작해도 바뀌지 않는 값(쿠버네티스는 파드 이름, 로컬은 정해 둔 이름)으로 채운다.
- **안 하면 어떻게 되나** : 재시작 한 번마다 에이전트 목록에 유령이 하나씩 늘고, 파드 단위 알림을 걸 기준이 없다.

| | |
|---|---|
| 건드리는 모듈 · 파일 | `docker-compose.dev.yml` · 서비스별 `Dockerfile` 또는 `otel/agent.properties` (결정에 따라), `README.md` · `AGENTS.md` |
| 건드리지 않는 것 | 앱 코드(에이전트는 비침습, ADR `#33`), Ingester 의 식별자 고르는 순서(backend `#58`) |
| 이 이슈 범위 밖 (후속) | 실제 쿠버네티스 매니페스트(`monimo-deploy` 는 아직 비어 있다). 여기서는 넣을 조각만 문서로 남긴다 |

**조사 들어가기 전에 내가 알던 것 (세 줄)** : 

- 
- 
- 

**알고 싶었던 것**

① 에이전트는 어디서 · 왜 UUID 를 만드나 ② 우리가 값을 주면 그 UUID 를 이기나 ③ 쿠버네티스에서 파드 이름은 언제 바뀌나 ④ 로컬 compose 에는 파드가 없는데 무엇을 넣나 ⑤ 남(OTel Operator · Pinpoint)은 어떻게 하나

## 2. 작업하기 전에 알아야 하는 것

### 2.1 `service.instance.id` : 에이전트의 이름표

OTel 이 정한 표준 속성으로, "같은 서비스의 여러 복제본 중 **이것 하나**" 를 가리킨다. `shop-order` 가 파드 두 개로 떠 있으면 `service.name` 은 둘 다 `shop-order` 이고 이 값만 다르다.

우리 백엔드는 이 값을 그대로 **`agent_id`** 로 쓴다. Ingester(`ingester/.../transform/OtlpValues.kt`)가 리소스 속성을 `service.instance.id` → `k8s.pod.name` → `host.name` 순으로 보고 처음 나온 것을 고른다(backend `#58`). 쇼핑몰은 이 셋 중 아무것도 직접 넣고 있지 않다.

### 2.2 지금은 누가 채우고 있나 : SDK 의 랜덤 UUID

아무도 안 넣는데 값이 있는 이유는 OTel Java SDK 의 `ServiceInstanceIdResourceProvider` 다. **JVM 이 뜰 때 `UUID.randomUUID()` 를 한 번** 만들어 넣는다. JVM 이 살아 있는 동안은 같고, 재시작하면 새 값이다.

- 우리 에이전트 2.31.1 jar 를 직접 열어 보니 `inst/io/opentelemetry/sdk/extension/incubator/resources/ServiceInstanceIdResourceProvider.classdata` 가 들어 있다. 즉 **incubator 판**이다
- 이 incubator 판은 "이미 `service.instance.id` 가 있으면 넣지 않는다"(`shouldApply`) 이고 가장 마지막 순서로 돈다. 그래서 **우리가 값을 주면 UUID 는 안 들어간다**

실제 우리 데이터 (로컬 ClickHouse, 2026-10-02) :

| service_name | agent_id | 시간 |
|---|---|---|
| shop-order | `77e7007f-53ed-…` | 16:18:45 ~ 16:18:56 (19건) |
| shop-order | `d7445e85-1e97-…` | 16:19:02 ~ 16:20:21 (465건) |

재시작 한 번에 `shop-order` 가 에이전트 둘로 쪼개졌다. 같은 표에 있는 `shop-order-7c9d5f-2xk8p` 같은 이름은 가짜 데이터(파드 이름 모양)라서, 화면은 이미 "파드 이름이 온다" 고 가정하고 만들어져 있다.

### 2.3 값을 넣는 길 세 가지와 그 우선순위

| 넣는 곳 | 예 | 우선순위 |
|---|---|---|
| JVM 시스템 프로퍼티 | `-Dotel.resource.attributes=service.instance.id=...` | 가장 높다 |
| 환경변수 | `OTEL_RESOURCE_ATTRIBUTES=service.instance.id=...` | 중간 |
| 설정 파일 | `otel/agent.properties` 의 `otel.resource.attributes=...` | 가장 낮다 |

여기서 함정이 하나 있다. **같은 키는 통째로 바뀐다.** 지금 `agent.properties` 에 `otel.resource.attributes=deployment.environment.name=local` 이 있는데, 환경변수로 `OTEL_RESOURCE_ATTRIBUTES=service.instance.id=order-1` 만 주면 `deployment.environment.name=local` 은 사라진다. 두 값을 합쳐 주지 않는다 (아직 로컬에서 재현하지 않았다 : 5 절에서 확인).

### 2.4 Downward API : 파드가 자기 이름을 아는 법

쿠버네티스가 파드 정보(이름 · 네임스페이스 · IP)를 컨테이너 환경변수로 넣어 주는 기능이다. 매니페스트에 이렇게 쓴다.

```yaml
env:
  - name: POD_NAME
    valueFrom: { fieldRef: { fieldPath: metadata.name } }   # 파드 이름이 여기 들어온다
  - name: OTEL_RESOURCE_ATTRIBUTES                            # 반드시 POD_NAME 보다 아래
    value: "service.instance.id=$(POD_NAME)"
```

`$(POD_NAME)` 은 쿠버네티스가 바꿔 주는 문법이고, **위에 먼저 정의된 변수만** 바뀐다. 순서가 틀리면 글자 `$(POD_NAME)` 이 그대로 들어가고 컨테이너는 아무 경고 없이 뜬다.

### 2.5 파드 이름은 언제 바뀌나

| 일 | 파드 이름 | 지금(UUID) | 파드 이름을 쓰면 |
|---|---|---|---|
| 컨테이너 재시작 (OOM · liveness 실패 · 앱 죽음) | 그대로 | 바뀜 | **그대로** |
| 롤링 배포 · `kubectl rollout restart` | 새 파드라 바뀜 (`shop-order-7c9d5f-2xk8p` 의 뒤 두 토막이 새로) | 바뀜 | 바뀜 |
| StatefulSet 파드 재생성 | 그대로 (`web-0`) | 바뀜 | 그대로 |

즉 파드 이름으로 고쳐지는 것은 **같은 파드 안의 재시작**이다. 배포하면 새 파드라 새 에이전트로 보이는 것은 그대로이고, 그건 실제로 새 인스턴스라서 맞는 동작이다.

### 2.6 데이터는 어디로 흐르나

```
[쇼핑몰 JVM]
  에이전트가 뜰 때 리소스 만들기 : 시스템 프로퍼티 > 환경변수 > agent.properties > (없으면) 랜덤 UUID
       │ 스팬 · 지표 · 로그마다 resource.service.instance.id 를 붙여 보냄
       ▼
Collector (OTLP 4317) ── Kafka raw ──▶ Ingester
                                    service.instance.id → k8s.pod.name → host.name 중 첫 값 = agent_id
                                    ▼
                               ClickHouse spans.agent_id · metrics_raw · logs
                                    ▼
                               화면의 에이전트 목록 · (나중에) 파드 단위 알림
```

| 질문 | 답 |
|---|---|
| 이름표가 틀리면 어디에 남나 | 이미 들어간 줄은 그 UUID 로 남는다. 고쳐도 과거 줄은 안 바뀐다 |
| 얼마나 남나 | `spans` TTL 93일 동안 옛 UUID 가 남는다 |
| 누가 바꾸나 | 쇼핑몰(넣는 쪽)만 고치면 된다. 백엔드는 이미 이 값을 먼저 본다 |

### 2.7 로컬 compose 에는 파드가 없다

compose 에는 Downward API 가 없다. 고를 수 있는 것은 셋이다 (자세한 비교는 5 절).

- 서비스마다 이름을 적어 둔다 : `service.instance.id=shop-order-local-1`
- 컨테이너 `hostname` 을 고정하고 그걸 쓴다
- 컨테이너 안에서 `$(hostname)` 을 읽어 넣는다 : 다만 docker 의 기본 hostname 은 컨테이너 ID 라 `docker compose up` 으로 다시 만들면 바뀐다

compose 파일의 `${VAR}` 는 **내 컴퓨터 쪽** 값으로 바뀐다. 컨테이너 안의 `HOSTNAME` 을 compose 파일에서 꺼내 쓸 수는 없다.

### 2.8 자주 헷갈리는 것

| 헷갈리는 것 | 실제 |
|---|---|
| 파드 이름을 쓰면 배포해도 같은 에이전트로 보인다 | 아니다. 컨테이너 재시작만 유지된다. 배포는 새 파드라 새 이름이다 |
| `OTEL_RESOURCE_ATTRIBUTES` 에 하나 더 적으면 `agent.properties` 값에 붙는다 | 아니다. 같은 키라 통째로 바뀐다. `deployment.environment.name` 을 같이 적어야 한다 |
| UUID 를 끄는 설정을 따로 해야 한다 | 아니다. 값을 주기만 하면 UUID provider 는 건너뛴다 (incubator 판 `shouldApply`) |
| 백엔드도 고쳐야 한다 | 아니다. Ingester 가 이미 `service.instance.id` 를 1순위로 본다 |
| `$(POD_NAME)` 은 쉘이 바꿔 준다 | 쿠버네티스가 바꾼다. 위에 정의된 변수만, 없으면 글자 그대로 남는다 |

---

## 3. 1차 질문 (사람 차례)

> 승조가 2 를 읽고 모르는 것을 여기에 적는다. 답은 바로 아래에 붙인다.
> **비어 있으면 아직 안 물은 것이다. 대신 채우지 않는다.**

- **Q. (내 정리 1) 같은 서비스의 파드가 여러 개 떠도 `service.name` 은 같고 `service.instance.id` 만 달라서 구분한다. 맞나**
  - A. 맞다. `shop-order` 파드 둘은 `service.name` 이 같고 `service.instance.id` 로 갈린다. 우리 백엔드는 이 값을 `agent_id` 로 쓴다.
- **Q. (내 정리 2) 지금은 그 값을 SDK 기본 UUID 로 쓰고 있고, 재시작할 때마다 바뀌는 게 문제다. 맞나**
  - A. 맞다. JVM 이 뜰 때 한 번 만들어서, 재시작하면 새 값이 된다.
- **Q. 값을 넣는 길 세 곳에 전부 같은 값을 넣어야 하나**
  - A. 아니다. 한 곳이면 된다. 셋 다 있으면 우선순위가 높은 한 곳의 값만 쓰인다. 다만 같은 키(`otel.resource.attributes`)라 높은 쪽이 낮은 쪽을 통째로 덮으므로, 높은 쪽에 넣을 때는 `deployment.environment.name=local` 도 같이 적어야 한다.
- **Q. k8s 매니페스트가 뭔가**
  - A. 쿠버네티스에 "이 컨테이너를 이런 설정으로 몇 개 띄워라" 를 적은 YAML 이다. compose 파일의 쿠버네티스판이고, 환경변수도 여기 적는다. 우리는 `monimo-deploy` 에 둘 예정인데 아직 비어 있다.
- **Q. 시스템 프로퍼티와 `agent.properties` 는 뭔가**
  - A. 시스템 프로퍼티는 `java -D이름=값` 처럼 JVM 을 띄우는 명령에 직접 붙이는 설정이다. 우리 Dockerfile 의 `ENTRYPOINT` 에 있는 `-Dotel.javaagent.configuration-file=...` 이 그 예다. `agent.properties` 는 그 줄이 가리키는 설정 파일(`otel/agent.properties`)로, 쇼핑몰 네 서비스가 같이 쓴다. 명령에 붙인 것이 가장 세고, 파일이 가장 약하다.
- **Q. Downward API 가 뭔가**
  - A. 쿠버네티스가 파드 자신의 정보(이름 · 네임스페이스 · IP)를 컨테이너 환경변수로 넣어 주는 기능이다. 앱은 자기 파드 이름을 모르는데, 매니페스트에 `fieldRef: metadata.name` 이라고 적으면 `POD_NAME` 같은 환경변수로 받는다.
- **Q. 세 길 중 우선순위가 가장 높은 시스템 프로퍼티가 가장 좋은 방법인가**
  - A. 아니다. 우선순위는 "셋 다 있을 때 누가 이기나" 일 뿐이다. 쿠버네티스의 `$(POD_NAME)` 치환은 **환경변수 값 안에서만** 되므로, 파드 이름을 넣는 정석은 환경변수다. 시스템 프로퍼티는 이미지(`ENTRYPOINT`)에 박히고, 설정 파일은 네 서비스가 같은 내용을 쓰므로 파드마다 다른 값을 넣을 수 없다. 길별 비교는 5 절.
- **Q. `service.instance.id` · `k8s.pod.name` · `host.name` 은 각각 어떻게 들어가나**
  - A. `service.instance.id` 는 우리가 주거나, 안 주면 SDK 가 UUID 를 넣는다. `host.name` 은 SDK 가 컨테이너의 hostname 을 읽어 자동으로 넣는다(docker 는 컨테이너 ID, 쿠버네티스는 기본이 파드 이름). `k8s.pod.name` 은 Java 에이전트가 스스로 넣지 않는다 : OTel Operator 나 Collector 의 k8sattributes 같은 바깥 도구가 넣거나 우리가 직접 적어야 한다. 지금은 1순위가 UUID 로 늘 차 있어서 2 · 3순위까지 내려가지 않는다. (`host.name` 이 실제로 실려 오는지는 로컬에서 확인 예정)
- **Q. `java -D이름=값` 의 `-D이름=값` 이 곧 `agent.properties` 인가. 모든 서비스가 그 파일을 쓰나**
  - A. 둘은 다른 길이다. `-D` 는 java 명령줄에 직접 붙이는 값이고, `agent.properties` 는 파일이다. 우리 `ENTRYPOINT` 의 `-Dotel.javaagent.configuration-file=/app/otel/agent.properties` 는 "설정 파일은 여기 있다" 는 **위치만 알려 주는** `-D` 다. 파일은 레포에 하나(`otel/agent.properties`)이고, 네 서비스 Dockerfile 이 각자 이미지 안으로 복사해 간다. 그래서 내용은 같고, 서비스마다 다른 값(이름표)은 이 파일에 넣을 수 없다.

- **Q. (내 정리 3) 쇼핑몰 에이전트에 이름표를 붙이는 일이고, 길은 Dockerfile(`-D`) · 환경변수(k8s 는 Downward API, compose 는 직접 적기) · `agent.properties` 셋이다. 하는 이유는 재시작마다 새 id 가 나와 에이전트 목록에 유령이 생기기 때문이다. 맞나**
  - A. 맞다. 두 군데만 고친다. compose 는 `.env` 가 아니라 `docker-compose.dev.yml` 의 `environment` 에 서비스마다 적는다(`.env` 는 내 컴퓨터 쪽 공통값이라 서비스마다 다른 이름에는 맞지 않는다). 그리고 "파드가 죽었다 다시 뜰 때" 는 둘로 나뉜다 : 같은 파드 안에서 컨테이너만 재시작하면 이름이 유지되고, 배포로 파드가 새로 만들어지면 이름도 새로 생긴다(실제로 새 인스턴스라 맞는 동작). `agent.properties` 는 네 서비스 · 모든 파드가 같은 내용이라 길로는 있어도 이름표 자리로는 못 쓴다.

---

## 4. 1차 정리

3 의 질문 여섯 개를 거친 뒤 확실해진 것만 묶는다.

- 확실한 것 :
  - 고치는 곳은 쇼핑몰 에이전트가 이름표(`service.instance.id`)를 정하는 **한 군데**다. Collector · Ingester · ClickHouse 는 그 값을 따라간다
  - 이름표를 주는 길은 `-D`(Dockerfile) · 환경변수(k8s 는 Downward API, compose 는 `environment`) · `agent.properties` 셋이고, 한 곳만 주면 된다
  - `agent.properties` 는 네 서비스 · 모든 파드가 같은 내용이라 이름표 자리로 못 쓴다
  - 환경변수로 주면 `agent.properties` 의 `deployment.environment.name=local` 이 사라진다
  - 파드 이름으로 고쳐지는 것은 같은 파드 안의 재시작까지다. 배포로 새 파드가 뜨면 새 이름이 맞다
- 아직 모르는 것 (5 에서 확인) :
  - 정말 우리 값이 UUID 를 이기나, 재시작해도 그대로인가
  - 환경변수가 `agent.properties` 값을 정말 통째로 지우나
  - 이름표를 아예 안 주고 2 · 3순위(`host.name`)로 내려가게 하는 길은 없나
  - 이름 모양 : 파드 이름 그대로인가, Operator 처럼 `네임스페이스.파드.컨테이너` 인가

---

## 5. 선택지와 설명

### 조사 프롬프트

원문은 같은 폴더의 [`prompts.md`](prompts.md) 에.

### 나온 선택지

| 방법 | 얻는 것 | 포기하는 것 | 구현 크기 |
|---|---|---|---|
| **A. 환경변수 `OTEL_RESOURCE_ATTRIBUTES`** (k8s 는 Downward API, compose 는 서비스마다 적기) | OTel Operator · k8s 문서와 같은 정석. 이미지를 다시 만들 필요 없음 | `deployment.environment.name` 을 환경변수 쪽에 같이 적어야 함. compose 이름은 손으로 적은 값 | compose 4줄 + README · 매니페스트 조각 |
| B. Dockerfile `ENTRYPOINT` 를 셸로 감싸 `-Dotel.resource.attributes=service.instance.id=${POD_NAME:-$(hostname)}` | 매니페스트 · compose 에 아무것도 안 적어도 됨 | Dockerfile 세 개를 셸 형식으로 바꿔야 함(신호 전달 · `exec` 신경). `-D` 가 가장 세서 배포 쪽에서 덮을 수 없음 | Dockerfile 3개 |
| C. 이름표를 끄고(`otel.resource.disabled.keys=service.instance.id`) `host.name` 으로 내려가게 | `agent.properties` 한 줄. k8s 는 hostname 이 기본으로 파드 이름이라 따로 안 넣어도 됨 | 표준 이름표가 비어서 백엔드의 3순위(대비책)에 기대게 됨. `hostNetwork` 같은 설정이면 hostname 이 노드 이름이 됨 | properties 1줄 + compose `hostname` 4줄 |
| D. OTel Operator 로 자동 주입 | 이름표 · `k8s.*` 속성을 Operator 가 다 채움 | 클러스터에 Operator 설치. 우리는 에이전트를 이미지에 직접 넣고 있어 방식이 겹침 | 배포 레포 큰 작업 (범위 밖) |

| 방법 | 표준 자리(`service.instance.id`)에 들어가나 | 배포 쪽에서 바꿀 수 있나 | 고칠 파일 수 | k8s 에서 따로 할 일 |
|---|---|---|---|---|
| **A.** | 예 | 예 (환경변수라 매니페스트가 정함) | compose 1 | Downward API env 2줄 |
| B. | 예 | 아니오 (`-D` 가 가장 셈) | Dockerfile 3 | `POD_NAME` env 1줄 (없으면 hostname) |
| C. | 아니오 (`host.name` 으로) | 반쯤 (hostname 은 파드 설정) | properties 1 + compose 1 | 없음 |
| D. | 예 | 예 | 배포 레포 | Operator 설치 |

A 는 "값은 배포하는 쪽이 정한다" 는 구조다. 이미지는 그대로 두고 k8s 매니페스트가 `$(POD_NAME)` 으로, compose 가 `shop-order-local-1` 같은 이름으로 채운다. 대가는 `deployment.environment.name` 이다 : 같은 키라서 환경변수에 이름표만 적으면 이 값이 사라지고, 실제로 사라지는 것을 아래에서 확인했다. 배포 환경에서는 어차피 `local` 이 아닌 값을 적어야 하므로 k8s 쪽에서는 손해가 아니고, compose 에만 한 번 더 적으면 된다.

B 는 이미지가 알아서 이름을 찾는다. 하지만 지금 `ENTRYPOINT` 는 셸을 거치지 않는 배열 형식이라 `$(hostname)` 같은 것을 못 쓴다. 셸로 감싸면 `exec` 를 빠뜨렸을 때 종료 신호가 java 까지 가지 않는 문제가 생긴다. 그리고 `-D` 는 가장 세서 배포 쪽에서 다른 이름을 주고 싶을 때 이길 수 없다.

C 는 가장 짧다. 이름표를 아예 비워 두면 Ingester 가 2 · 3순위로 내려가 `host.name` 을 쓰고, k8s 파드의 hostname 은 기본이 파드 이름이라 결과가 A 와 거의 같다. 다만 표준 자리를 비우고 백엔드의 대비책에 기대는 것이라, 나중에 Collector 나 다른 도구가 `service.instance.id` 를 보는 순간 어긋난다. compose 는 hostname 을 정해 주지 않으면 컨테이너 ID 라 다시 만들 때마다 바뀐다.

D 는 지금 범위 밖이다. 쇼핑몰은 에이전트를 이미지에 직접 넣었고(`#12`), Operator 를 쓰면 같은 일을 두 곳이 하게 된다.

이름 모양도 정해야 한다. 파드 이름(`shop-order-7c9d5f-2xk8p`) 그대로 쓰면 다른 네임스페이스에 같은 이름이 있을 때 겹칠 수 있다. OTel 의 k8s 가이드와 Operator 는 `네임스페이스.파드.컨테이너` 를 쓴다. 시맨틱 컨벤션은 "안정적인 값을 쓸 거면 그걸로 UUID v5 를 만들라" 고 권하는데 SHOULD 수준이다. 우리 화면의 가짜 데이터는 파드 이름 모양(`shop-order-7c9d5f-2xk8p`)이다.

### 우리 데이터로 확인한 것

`monimo/shop-payment:dev` 를 지표 내보내기를 화면 출력(`logging-otlp`)으로 바꿔 띄우고, 실려 나가는 리소스 속성을 직접 읽었다.

| 확인 | 값 |
|---|---|
| 아무것도 안 줌 | `service.instance.id=1d0deca2-…`, `deployment.environment.name=local`, `host.name=c572be6a09b2`(컨테이너 ID) |
| 같은 컨테이너 재시작 | `1d0deca2-…` → `e2624ae7-…` **바뀜** (문제 재현) |
| 환경변수에 이름표만 | `service.instance.id=shop-payment-local-1`, **`deployment.environment.name` 없음** (2.3 의 함정 확인) |
| 환경변수에 이름표 + 환경 | 둘 다 있음 |
| 환경변수 이름표 + 재시작 | `shop-payment-local-1` **그대로** |
| UUID 끄기(`OTEL_RESOURCE_DISABLED_KEYS`) + `--hostname shop-payment-local-1` | `service.instance.id` 없음, `host.name=shop-payment-local-1` (C 가 동작함) |
| ClickHouse `spans` 의 shop-order | 재시작 한 번에 `77e7007f-…` 19건, `d7445e85-…` 465건으로 쪼개짐 (2026-10-02) |

### 남이 어떻게 하나

| 도구 · 제품 | 어떻게 하나 | 출처 |
|---|---|---|
| OTel Operator | 파드 annotation 이 있으면 그것, 없으면 `네임스페이스.파드.컨테이너`. 재시작 횟수는 일부러 안 넣는다(크래시 루프에서 id 가 계속 바뀌지 않게) | https://opentelemetry.io/docs/specs/semconv/non-normative/k8s-attributes/ |
| OTel 시맨틱 컨벤션 | 기본은 랜덤 UUID, 안정적인 값을 원하면 고유 ID 로 UUID v5 (SHOULD) | https://opentelemetry.io/docs/specs/semconv/registry/attributes/service/ |
| Pinpoint | `-Dpinpoint.agentId` 를 사람이 정한다(전역 유일). 2.2.0 부터 안 주면 자동 생성. 4.0 계획은 기동마다 새 UUID + 표시용 `agentName` 분리 | https://pinpoint-apm.github.io/pinpoint/installation.html , https://github.com/pinpoint-apm/pinpoint/issues/14379 |
| Pinpoint 사용자들 | 예전 agentId 24자 제한 때문에 파드 이름을 그대로 못 넣어 `앱-파드이름 뒤 5자` 로 줄여 썼다 | https://github.com/pinpoint-apm/pinpoint/issues/5343 |

### AI 가 틀렸거나 덜 맞았던 것 (남겨 둔다)

- 조사 답이 "2.31.1 에 incubator 판인지 stable 판인지 미확인, 아마 incubator" 라고 했다. 추정을 그대로 쓰지 않고 jar 를 열어 incubator 판임을 확인했다
- 처음 2 절 설명에서 "세 길 중 우선순위가 가장 높은 것" 과 "가장 좋은 것" 이 섞여 읽힐 여지가 있었다. 3 절 질문으로 바로잡았다 : 이름표 자리는 환경변수가 정석이다
- 조사 답의 v2.31.1 릴리스 날짜 · SDK 버전 요약이 서로 맞지 않았다(조사 에이전트 스스로 신뢰하지 않음). 쓰지 않았다

### 라이브러리 · 레포를 직접 열어 확인한 것

| 확인 | 결과 |
|---|---|
| 에이전트 2.31.1 jar 안의 UUID provider | `inst/io/opentelemetry/sdk/extension/incubator/resources/ServiceInstanceIdResourceProvider.classdata` |
| Ingester 의 식별자 순서 | backend `ingester/.../transform/OtlpValues.kt`, 테스트 `SpanTranslatorTest` "service.instance.id 와 k8s.pod.name 이 다 있으면 표준인 service.instance.id 를 먼저 쓴다" |
| 지금 Dockerfile `ENTRYPOINT` | 배열 형식(셸 없음). B 를 고르면 바꿔야 한다 |

### 확인 못 한 것

- k8s 파드의 hostname 이 정말 파드 이름인지 : k8s 문서(https://kubernetes.io/docs/concepts/services-networking/dns-pod-service/)만 봤고 클러스터에서 재현하지 않았다. C 를 고르면 필요한 확인이다
- Downward API 조각을 실제 클러스터에 넣어 본 적이 없다 (`monimo-deploy` 가 비어 있음)
- compose 기본 컨테이너 이름 형식(`<프로젝트>-<서비스>-<번호>`)은 블로그 출처뿐이다
- `host.name` 이 실제로 Collector · Ingester 를 거쳐 `agent_id` 까지 내려가는지는 위 컨테이너 실험(화면 출력)만 봤고 파이프라인으로는 안 봤다

## 6. 2차 질문 (사람 차례)

- **Q. 세 길(시스템 프로퍼티 · 환경변수 · `agent.properties`)과 우선순위는 무슨 관계인가**
  - A. 셋은 같은 설정(`otel.resource.attributes`)을 적는 세 자리다. 이름 모양만 다르다(`-Dotel.resource.attributes` · `OTEL_RESOURCE_ATTRIBUTES` · 파일의 `otel.resource.attributes`). 우선순위는 여러 곳에 적혔을 때 누가 이기나이고, 합치지 않고 센 쪽 하나만 쓴다. 선택지 A 는 환경변수, B 는 시스템 프로퍼티, C 는 `agent.properties` 를 쓴다.
- **Q. 세 방식의 장단점은**
  - A. `-D` 는 가장 세고 늘 적용되지만 이미지에 박혀 파드마다 다른 값을 넣으려면 셸이 필요하고 배포 쪽에서 덮을 수 없다. 환경변수는 Downward API 로 파드마다 값을 넣기 쉽고 다시 빌드할 필요가 없지만 `deployment.environment.name` 을 같이 적어야 하고 순서를 틀리면 경고 없이 글자 그대로 들어간다. `agent.properties` 는 공통 기본값 자리로 좋지만 모두가 같은 내용이라 이름표를 못 넣는다.
- **Q. A 로 간다. 이름 모양은 파드 이름 그대로와 `네임스페이스.파드.컨테이너` 중 무엇이 나은가**
  - A. 지금은 파드 이름 그대로를 권한다. 표준이 요구하는 것은 "같은 서비스 안에서 유일" 이고, 한 네임스페이스 안의 파드 이름은 쿠버네티스가 이미 유일하게 만든다. 쇼핑몰은 파드 하나에 앱 컨테이너 하나라 컨테이너 이름은 늘 같은 글자가 붙을 뿐이다. 화면에서 읽기도 쉽고(`shop-order-7c9d5f-2xk8p`), Watchdog · 화면 가짜 데이터도 이 모양이다. 네임스페이스와 파드 이름은 `k8s.namespace.name` · `k8s.pod.name` 으로 따로 실어 두면 나중에 필요할 때 쓸 수 있다. 되돌리는 조건 : 같은 백엔드로 두 네임스페이스(예: staging · prod)의 쇼핑몰이 같이 들어오게 되면 이름이 겹칠 수 있으니 `네임스페이스.파드` 로 바꾼다.

---

## 7. 2차 정리

선택지를 다 본 뒤의 상태다. 결정은 8 에서 승조가 글로 내린다.

- 좁혀진 선택지 : **A (환경변수)** 하나. B(`-D`)는 셸 래퍼 · `exec` · 다시 빌드 · 배포 쪽에서 못 덮음 때문에, C(`agent.properties` + UUID 끄기)는 표준 자리를 비우고 백엔드 대비책에 기대기 때문에 빠졌다. D(Operator)는 범위 밖
- 결정을 가르는 기준 : 파드마다 다른 값을 넣기 쉬운가, 배포하는 쪽이 값을 정하나, 표준 자리(`service.instance.id`)에 들어가나
- 무엇을 정해야 하나 :
  - 이름 모양 : 파드 이름 그대로 (6 절). 되돌리는 조건은 두 네임스페이스가 같은 백엔드로 들어올 때
  - compose 에 적을 이름 : `shop-<서비스>-local-1` 꼴
  - `deployment.environment.name=local` 을 compose 환경변수에도 같이 적기 (`agent.properties` 는 그대로 둠 : 환경변수 없이 실행할 때의 기본값)
  - k8s 조각을 어디에 남기나 : `monimo-deploy` 가 비어 있으니 쇼핑몰 README 에 조각으로

### 여기서 나온 면접 질문

1. 재시작할 때마다 에이전트가 새로 붙은 것처럼 보였다. 원인은 무엇이고 어디를 고쳤나
2. 이름표를 넣을 수 있는 자리가 셋인데 왜 환경변수를 골랐나. `-D` 는 왜 안 되나
3. 파드 이름을 쓰면 배포 후에도 같은 에이전트로 보이나
4. 왜 `네임스페이스.파드.컨테이너` 가 아니라 파드 이름 그대로인가. 언제 바꿔야 하나
5. 매니페스트에서 `OTEL_RESOURCE_ATTRIBUTES` 를 `POD_NAME` 보다 위에 적으면 어떻게 되나

---

## 8. 구현 프롬프트 (사람 차례)

> 승조가 7 을 읽고 쓴 결정 원문이다. AI 가 빠진 세 가지(`-D` 는 배포 쪽에서 못 덮음 · `deployment.environment.name` 같이 적기 · compose 는 이름 직접)와 되돌리는 조건을 끼워 넣자고 제안했고, 승조가 그 안을 받아들였다. 같은 원문이 [`prompts.md`](prompts.md) 에 있다.

```
방식이 3가지 방식이 있는데 환경변수에 사용하는 방식 사용과 이름은 파드 이름 설정으로 가자
시스템 프로퍼티의 경우 Dockerfile을 고치는 방법인데 파드마다 다른 값을 넣으려면 ENTRYPOINT를 셸 스크립트로 바꿔야 하고
exec를 빼먹으면 종료 신호가 java까지 전달되지 않기도 하고 값 변경 시 이미지 빌드를 다시해야하고 가장 센 자리라 배포 쪽(매니페스트)에서 덮을 수도 없으니까 이러한 문제들이 있으니까 패스하고 agent.properties는 모든 파드가 같은 내용이라 이름표 넣는게 무의미 하니까
환경 변수 에 적는 방법으로 하고 이 방식이 파드 이름 설정으로 하는 거랑 또 이어지니까 k8s의 Downward API로 파드 이름을 자동으로 채울 수 있으니까 이방식으로 가자
환경변수로 넣으면 agent.properties의 deployment.environment.name=local 이 사라지니까 같이 적어주고
로컬 compose는 Downward API가 없으니까 shop-order-local-1 처럼 이름을 직접 적자
나중에 두 네임스페이스(staging · prod)의 쇼핑몰이 같은 백엔드로 같이 들어오게 되면 이름이 겹칠 수 있으니까 그때는 네임스페이스.파드 로 바꾸자
```
