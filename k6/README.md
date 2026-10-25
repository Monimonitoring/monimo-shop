# k6 시나리오

쇼핑몰(감시 대상)에 부하를 걸고 에러 · 지연을 섞는다. 알림 규칙과 화면을 시험하는 재료다.
Phase 1a 데모 문장 "k6 로 에러를 내면 N초 안에 슬랙에 알림이 온다"(ADR #19)가 여기서 시작한다.

## 실행

쇼핑몰을 compose 로 켠 뒤 레포 루트에서 실행한다. 도커 이미지를 쓰므로 k6 를 설치하지 않아도 되고 버전이 고정된다.

```bash
docker compose -f docker-compose.dev.yml up -d --build --wait

docker run --rm --network monimo-dev -v "$PWD/k6:/scripts" -e BASE_URL=http://gateway:8090 \
  <아래 표의 -e 값들> grafana/k6:2.3.0 run /scripts/order.js
```

| 목적 | 넣을 값 | 결과 |
|---|---|---|
| smoke (살아 있나) | `-e RATE=1 -e DURATION=10s` | 정상 주문 10건 |
| 기본 부하 | (기본값) 초당 10건 · 1분 | 정상 주문 600건 |
| 에러 주입 (5xx 비율 규칙) | `-e ERROR_RATE=0.3 -e DURATION=2m` | 1,200건 중 360건 502 |
| 지연 주입 (p95 지연 규칙) | `-e SLOW_RATE=0.2 -e DURATION=2m` | 1,200건 중 240건 2초 이상 |
| 외부 결제사 장애 | `-e PG_ERROR_RATE=0.3 -e DURATION=2m` | 1,200건 중 360건 502 (결제사 503 이 원인) |
| 재고 서비스 장애 | `-e INVENTORY_ERROR_RATE=0.3 -e DURATION=2m` | 1,200건 중 360건 502 (재고 500 이 원인, 결제는 안 부른다) |
| 재고 서비스 지연 | `-e INVENTORY_SLOW_RATE=0.2 -e DURATION=2m` | 1,200건 중 240건 1.5초 이상 |
| 품절 (4xx 비율 규칙) | `-e SOLDOUT_RATE=0.3 -e DURATION=2m` | 1,200건 중 360건 409 (재고 0 인 `P-SOLDOUT` 주문) |
| 재고 조회 끄기 | `-e STOCK_LOOKUP_RATE=0` | 주문만. Service map 에 inventory → Redis 화살표가 안 생긴다 |

로컬에 k6 가 있으면 `k6 run -e RATE=1 -e DURATION=10s k6/order.js` 도 된다(기본 주소는 게이트웨이 `http://localhost:8090`).

## 환경변수

| 이름 | 기본값 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8090` | 진입 주소(게이트웨이). 도커로 돌릴 때는 `http://gateway:8090`. 게이트웨이를 빼고 주문 서비스를 직접 치려면 `http://order:8091` |
| `RATE` | 10 | 초당 주문 수. 응답이 느려져도 유지된다(constant-arrival-rate) |
| `DURATION` | `1m` | 실행 시간 |
| `ERROR_RATE` | 0 | 결제 실패(`X-Shop-Fault: payment-error` → 502) 비율, 0 ~ 1 |
| `SLOW_RATE` | 0 | 결제 2초 지연(`X-Shop-Fault: payment-slow`) 비율, 0 ~ 1 |
| `PG_ERROR_RATE` | 0 | 외부 결제사 503(`X-Shop-Fault: pg-error` → 주문 502) 비율, 0 ~ 1 |
| `PG_SLOW_RATE` | 0 | 외부 결제사 1.5초 지연(`X-Shop-Fault: pg-slow`) 비율, 0 ~ 1 |
| `INVENTORY_ERROR_RATE` | 0 | 재고 서비스 500(`X-Shop-Fault: inventory-error` → 주문 502) 비율, 0 ~ 1 |
| `INVENTORY_SLOW_RATE` | 0 | 재고 서비스 1.5초 지연(`X-Shop-Fault: inventory-slow`) 비율, 0 ~ 1 |
| `SOLDOUT_RATE` | 0 | 품절 상품 `P-SOLDOUT` 주문(→ 409 `SOLD_OUT`) 비율, 0 ~ 1. 헤더가 아니라 상품으로 만든다 |
| `STOCK_LOOKUP_RATE` | 0.5 | 주문 전에 `GET /api/stock/{id}` 로 재고를 먼저 보는 손님 비율, 0 ~ 1. 장애 비율과는 독립 |

장애 비율 일곱 개의 합은 1 이하여야 한다. 순번 구간은 payment-error → payment-slow → pg-error → pg-slow → inventory-error → inventory-slow → soldout → 정상 순서다.

## 읽는 법

- **비율은 정확하다.** 요청 순번을 100 으로 나눈 나머지로 종류를 정한다. `ERROR_RATE=0.3` 이면 100건마다 앞 30건이 502 다. 난수가 아니라서 몇 번을 돌려도 같다.
- **요청 수는 1건 더 나올 수 있다.** k6 가 실행 시간이 끝나는 경계에서 1건을 더 넣는다(초당 5건 · 20초 → 101건). 101번째는 순번 100 이라 다시 앞쪽(에러 구간)에 들어가서, 그때 502 는 30건이 아니라 31건이다.
- **checks 가 100% 가 아니면 실패로 끝난다.** 에러 주입 요청이 502 인 것은 "기대한 결과"라 통과다. 정상 요청이 502 거나 지연이 2초 안 되면 실패다.
- **요청마다 `fault` 태그가 붙는다.** 결과 요약에서 `none` · `payment-error` · `payment-slow` 별로 볼 수 있다.
- 데이터를 Collector 까지 보내려면 monimo-backend 에서 `docker compose --profile collector up -d --wait` 를 같이 켠다.
