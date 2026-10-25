# `#30` 재시작해도 그대로인 에이전트 이름표 (`service.instance.id`)

> **이 작업의 기술 설계 문서 한 장.** 문제 → 선택지 → 결정 → 장애가 나면 순서로 읽으면 끝난다.
> 하네스 규칙과 틀은 backend [`docs/seungjo/`](https://github.com/2026-techeer-project-team-b/monimo-backend/blob/HEAD/docs/seungjo/README.md) 가 정본이다. 이 이슈는 쇼핑몰 레포에서 한 작업이라 코드와 같은 PR 에 둔다.

- 2026-10-06 / 승조(`@SeungJo-02`) / ADR 없음 (설정 한 줄 결정이라 research ⑧ 원문이 결정 기록) / 이슈 `#30`
- 유저 플로우에서 어디 : **에이전트**(쇼핑몰 안)가 이름표를 정하는 자리. Collector → Kafka → Ingester → ClickHouse 는 그 값을 `agent_id` 로 따라간다

## 문제

에이전트는 이름표(`service.instance.id`)를 안 주면 JVM 이 뜰 때마다 랜덤 UUID 를 만든다(OTel SDK `ServiceInstanceIdResourceProvider`, 에이전트 2.31.1 은 incubator 판). 백엔드 Ingester 는 이 값을 `agent_id` 로 쓰므로(backend `#58`), 재시작 한 번마다 옛 에이전트가 사라지고 새 에이전트가 붙은 것처럼 보인다.

| 재 본 것 | 값 |
|---|---|
| ClickHouse `spans`, `shop-order`, 2026-10-02 재시작 한 번 | `77e7007f-…` 19건 → `d7445e85-…` 465건으로 쪼개짐 |
| payment 컨테이너를 그대로 재시작 | `1d0deca2-…` → `e2624ae7-…` |

**무엇이 깨지나** : 에이전트 목록에 유령이 쌓이고, 파드 단위 알림을 걸 기준이 없다.

## 선택지

| 방법 | 얻는 것 | 포기하는 것 | 구현 크기 |
|---|---|---|---|
| **A. 환경변수 `OTEL_RESOURCE_ATTRIBUTES`** (k8s 는 Downward API) | 정석. 파드마다 값 넣기 쉬움, 다시 빌드 없음, 배포 쪽이 정함 | `deployment.environment.name` 을 같이 적어야 함. compose 는 이름을 손으로 | compose 3줄 + 문서 |
| B. Dockerfile `-D` 를 셸로 감싸기 | 매니페스트에 안 적어도 됨 | 셸 래퍼 · `exec` · 다시 빌드, 가장 센 자리라 배포 쪽에서 못 덮음 | Dockerfile 3개 |
| C. `agent.properties` 로 UUID 끄고 `host.name` 으로 | 한 줄 | 표준 자리가 비고 백엔드 대비책(3순위)에 기댐 | 1줄 + compose |
| D. OTel Operator | 전부 자동 | 우리는 에이전트를 이미지에 넣어 겹침 | 범위 밖 |

출처 링크 · 우리 데이터 대조 · AI 가 틀렸던 것은 [`research.md`](research.md).

## 결정

- **고른 것** : A. 이름은 파드 이름 그대로. compose 는 `shop-<서비스>-local-1`, `deployment.environment.name=local` 을 같은 줄에
- **버린 것과 이유** : B 는 셸 래퍼 · `exec` 누락 위험 · 다시 빌드 · 배포 쪽에서 못 덮음. C 는 `agent.properties` 가 모든 파드에 같은 내용이라 이름표 자리가 아니고, 우회하면 표준 자리가 빈다. `네임스페이스.파드.컨테이너` 는 파드에 컨테이너가 하나라 덧붙는 글자가 늘 같고, 한 네임스페이스 안의 파드 이름은 이미 유일하다
- **되돌리는 조건** : 같은 백엔드로 두 네임스페이스(staging · prod)의 쇼핑몰이 같이 들어오면 `네임스페이스.파드` 로 바꾼다

결정 원문은 [`research.md`](research.md) 8 절과 [`prompts.md`](prompts.md).

## 장애가 나면

| 무엇이 죽으면 · 틀리면 | 어떻게 되나 | 어떻게 알아채나 |
|---|---|---|
| 환경변수를 빠뜨림 (새 서비스 · 새 매니페스트) | UUID 로 돌아가 재시작마다 새 에이전트 | 에이전트 목록에 UUID 모양 이름이 보인다 |
| 환경변수에 이름표만 적음 | `deployment.environment.name` 이 빠진다 (실험으로 확인) | 리소스 속성에 환경이 없다 |
| 매니페스트에서 `OTEL_RESOURCE_ATTRIBUTES` 를 `POD_NAME` 위에 적음 | 모든 파드 이름표가 글자 `$(POD_NAME)` 으로 같아져 **파드가 하나로 합쳐 보인다**. 경고 없음 | `agent_id` 에 `$(` 가 보인다 |
| compose 에서 같은 서비스를 `--scale` 로 여럿 띄움 | 이름표가 같아 한 에이전트로 합쳐진다 | 로컬 전용 한계. 여러 개가 필요하면 서비스를 나눠 적는다 |
| 롤링 배포 · `rollout restart` | 새 파드라 새 이름 = 새 에이전트 | 정상 동작. 실제로 새 인스턴스다 |

## 어떻게 확인했나

- 빌드 : `./gradlew build` 통과 (코드 변경 없음, 회귀 확인)
- E2E : compose 로 띄워 주문 3건 → order 재시작 → 3건 → 재시작 → 3건. order JVM 기동 3번, ClickHouse 에서 `agent_id` 는 서비스마다 하나 (spans : gateway 18 · order 38 · payment 18, metrics_raw : 186 · 240 · 248, 전부 `shop-<서비스>-local-1`)
- 일부러 깨뜨려 본 것 : 환경변수에 이름표만 넣으면 `deployment.environment.name` 이 사라지는 것, 아무것도 안 주면 재시작에 UUID 가 바뀌는 것 (payment 컨테이너 `logging-otlp` 출력으로)
- 쿠버네티스 : 클러스터가 없어 Downward API 조각은 **시험하지 않았다** (`monimo-deploy` 가 비어 있음)
- CI : PR 에서 build · smoke

## 결과물

- 새 파일 : `docs/seungjo/30-service-instance-id/` (README · research · prompts)
- 수정 : `docker-compose.dev.yml`(3줄 + 주석) · `otel/agent.properties`(주석) · `README.md`(이름표 설명 · k8s 조각 · 환경변수 표) · `AGENTS.md`(§5 · §6) · `docs/prompts/README.md`(목록)

## 읽는 순서

1. [`prompts.md`](prompts.md)
2. [`research.md`](research.md)

## 이 이슈에서 배운 것 (세 줄)

- 에이전트 설정은 세 자리(`-D` · 환경변수 · 설정 파일)에 적을 수 있고, 겹치면 합치지 않고 센 쪽 하나만 쓴다
- 파드 이름으로 고쳐지는 것은 같은 파드 안의 재시작까지다. 배포하면 새 에이전트가 맞다
- 조사 답의 "아마 incubator 판" 같은 추정은 jar 를 직접 열어 확인했다
