# 에이전트 오버헤드 측정

쇼핑몰에 OTel Java Agent(+ 스레드 덤프 Extension)를 **붙였을 때와 뗐을 때**를 같은 부하로 돌려 CPU · 메모리 차이를 잰다.
목표는 비기능 요구사항 "대상 앱 CPU 3% 이내, 메모리 100MB 이내" (backend `docs/design/10-requirements.md`). 왜 이렇게 재는지와 결과는 [`docs/seungjo/36-agent-overhead/`](../../docs/seungjo/36-agent-overhead/README.md).

## 돌리기

monimo-backend 의 Collector 가 떠 있어야 붙인 쪽이 실제로 데이터를 보낸다(`docker compose --profile collector up -d --wait`). 쇼핑몰은 **내려 둔 상태**에서 시작한다(스크립트가 직접 띄우고 내린다. 떠 있으면 멈춘다).

```bash
docker compose -f docker-compose.dev.yml build          # 이미지가 최신인지 먼저
perf/overhead/run.sh on 20                               # 한 회차 : 붙임, 초당 20건, 60초
perf/overhead/run.sh off 20                              # 한 회차 : 뗌
perf/overhead/suite.sh                                   # 전부 (약 2시간 40분)
python3 perf/overhead/summarize.py                       # 서비스별 중앙값 표
```

결과는 `perf/overhead/out/results.csv` 에 한 줄씩 쌓인다(깃에 안 올림). 측정 중에는 같은 맥북에서 빌드 같은 무거운 일을 하지 않는다.

## 한 회차가 하는 일

```
compose up --wait (네 서비스 CPU 1개 · 메모리 1GB 제한)   ← healthy = 부팅 끝. 부팅 CPU 는 안 잰다
  → k6 40초 워밍업 (버림)                                  ← JIT 데우기
  → cgroup 읽기 : cpu.stat usage_usec · memory.current
  → k6 60초 (constant-arrival-rate, 초당 N건)
  → cgroup 다시 읽기, k6 요청 수 · p95 · 실패
  → compose down
```

CPU 는 구간 앞뒤 누적값의 차이(그 60초에 쓴 CPU 초), 메모리는 끝난 순간 값이다. 둘 다 컨테이너 바깥(cgroup)에서 읽으므로 에이전트가 없어도 똑같이 잰다. 에이전트가 보내는 `jvm.*` 메트릭은 뗀 쪽에 없어서 비교에 쓰지 않는다.

## 변형

| 이름 | 덮어쓰기 | 무엇 |
|---|---|---|
| `on` | (없음) | 지금 이미지 그대로 |
| `off` | `no-agent.yml` | `entrypoint` 를 `java -jar /app/app.jar` 로. `-javaagent` 가 아예 없다 |
| `metrics-off` | `metrics-off.yml` | 붙이고 메트릭만 끔. 원인 찾기용 |
| `instr-min` | `instr-min.yml` | 계측을 다 끄고 쇼핑몰이 쓰는 것(톰캣 · Spring MVC · HTTP 클라이언트 · JDBC · Lettuce · 로그 · JVM 메트릭 · 스레드 전파)만 켬. 원인 찾기용 |
| `nmt-on` · `nmt-off` | `nmt.yml` | 메모리를 항목별로 쪼개 `out/nmt-*.txt` 에 남김. NMT 자체가 느려서 CPU 비교에는 안 쓴다 |

모든 변형에 `limits.yml` (CPU 1개 · 메모리 1GB)이 깔린다. `metrics-off` · `instr-min` 은 실험용이지 운영 설정이 아니다.

## 숫자 읽는 법

- **상대 증가** : (붙임 CPU 초 − 뗌 CPU 초) / 뗌 CPU 초. #36 의 판정 기준
- **절대 증가** : 1코어 기준 %p. 부하에 비례해 커지므로 초당 몇 건인지 같이 읽는다
- **주문당** : 네 서비스 CPU 합 / 주문 수(초당 건수 × 초). 주문 절반은 재고 조회(GET)를 하나 더 동반하니 주문 1건 = HTTP 요청 1.5건. 부하와 상관없는 "에이전트 단가"
- **메모리 차이** : 부하 중 cgroup `memory.current` 차이. 힙이 GC 따라 출렁여 회차마다 수십 MB 흔들린다

맥북 한 대에서 재므로 한두 회차는 튄다. 5회 이상 번갈아 돌린 중앙값을 본다.

## 알아 둘 것

- 측정 구간은 k6 컨테이너 기동 · 종료(1 ~ 3초, 부하 없음)를 포함한 약 60초다. %p 는 60초로 나눈다
- MySQL 볼륨을 회차 사이에 지우지 않아 주문 표가 쌓인다. 번갈아 돌려 한쪽에 몰리지 않게 한다
- cgroup v2 전제다. 값을 못 읽거나 k6 요청이 95% 미만이면 그 회차는 기록하지 않고 멈춘다(`out/k6.log` · `out/compose-up.log`)
- 쇼핑몰 컨테이너가 하나라도(MySQL · Redis 포함) 떠 있으면 시작하지 않는다. 끝나면 쇼핑몰 프로젝트 전체를 내린다
