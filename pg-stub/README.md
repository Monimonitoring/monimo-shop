# pg-stub — 더미 외부 결제사

결제 서비스가 부르는 "밖의 결제사" 역할을 하는 WireMock(`wiremock/wiremock:3.13.2`) 컨테이너다. 코드는 없고 `mappings/` 의 JSON 응답 정의만 있다.
진짜 외부 시스템처럼 **에이전트를 붙이지 않는다.** 그래서 Service map 에는 payment 가 밖으로 나가는 호출 노드로만 보인다.

| 요청 | 조건 | 응답 |
|---|---|---|
| `POST /v1/approvals` | 기본 | 200 `{approvalId: "pg-xxxxxxxx", status: "APPROVED"}` |
| 〃 | 헤더 `X-Shop-Fault: pg-error` | 503 `{error: "pg unavailable"}` → payment 502 → order 502 |
| 〃 | 헤더 `X-Shop-Fault: pg-slow` | 1.5초 뒤 200 |

compose 서비스 이름은 `pg-stub`(컨테이너 안 8080), 내 컴퓨터에서는 `localhost:8099`. 응답 정의를 바꾸면 `docker compose -f docker-compose.dev.yml restart pg-stub`.
상태 확인: `curl localhost:8099/__admin/health`
