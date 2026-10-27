#!/usr/bin/env bash
# 한 회차 : 쇼핑몰을 띄우고 → 워밍업 → 고정 부하로 재고 → 내린다. 결과를 CSV 한 줄씩(서비스마다) 덧붙인다.
#
#   perf/overhead/run.sh <변형> <초당 건수> [측정 초] [CSV 경로]
#   변형 : on (그대로) · off (에이전트 뗌) · metrics-off · instr-min · nmt-on · nmt-off
#          interval-30 · interval-60 (메트릭 전송 간격) · sampling-10 · sampling-1 (앱 쪽 샘플링)
#          batch-size · batch-delay (한 번에 보내는 묶음 · 보내는 주기)  ← 여기까지 #51
#   초당 건수 0 이면 부하 없이 가만히 둔 채 잰다
#
# CPU 는 컨테이너 cgroup 의 cpu.stat usage_usec(지금까지 쓴 CPU 시간 누적)를 측정 구간 앞뒤로 읽어 뺀다.
# 메모리는 측정이 끝난 순간의 memory.current. 둘 다 JVM 바깥에서 읽으므로 에이전트가 있든 없든 똑같이 잰다 (#36)
set -euo pipefail

VARIANT=${1:?변형 (on · off · metrics-off · instr-min · nmt-on · nmt-off · interval-30 · interval-60 · sampling-10 · sampling-1 · batch-size · batch-delay)}
RATE=${2:?초당 건수}
SECS=${3:-60}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
HERE="$ROOT/perf/overhead"
CSV=${4:-$HERE/out/results.csv}
WARMUP_SECS=40   # 막 뜬 JVM 은 JIT 전이라 무겁다. 이 구간은 버린다
SERVICES="gateway order payment inventory"
K6_IMAGE=grafana/k6:2.3.0
COLLECTOR_METRICS=${COLLECTOR_METRICS:-http://localhost:18081/actuator/metrics}

# 수집기가 받은 건수(누적). 신호별로 센다. 못 읽으면 빈 값으로 둔다 (뗌 회차는 수집기가 없어도 돌 수 있다)
#
# 주의 : 이 값은 "보낸 횟수" 가 아니라 "받은 건수(데이터 포인트 · 스팬 개수)" 다 (OtlpReceiveCounter).
#   전송 횟수를 알려면 한 번에 몇 건이 오는지(P)로 나눠야 하고, P 가 회차마다 같은지는 이 열로 확인한다.
#   그리고 이 카운터는 발신자를 안 가린다 : 쇼핑몰 말고 다른 것이 같은 수집기로 보내면 섞인다.
#   뗌(off) 회차의 값이 그 "다른 것" 의 몫이라 배경값으로 쓸 수 있다 (#51 research.md 7절)
received() { # $1 = traces · metrics · logs
  curl -fsS --max-time 3 "$COLLECTOR_METRICS/monimo.collector.otlp.received?tag=signal:$1" 2>/dev/null \
    | python3 -c 'import sys,json; print(int(json.load(sys.stdin)["measurements"][0]["value"]))' 2>/dev/null || echo ""
}

FILES=(-f "$ROOT/docker-compose.dev.yml" -f "$HERE/limits.yml")
case "$VARIANT" in
  on)          ;;
  off)         FILES+=(-f "$HERE/no-agent.yml") ;;
  metrics-off) FILES+=(-f "$HERE/metrics-off.yml") ;;
  instr-min)   FILES+=(-f "$HERE/instr-min.yml") ;;
  nmt-on)      FILES+=(-f "$HERE/nmt.yml") ;;
  nmt-off)     FILES+=(-f "$HERE/no-agent.yml" -f "$HERE/nmt.yml") ;;
  interval-30) FILES+=(-f "$HERE/interval-30.yml") ;;
  interval-60) FILES+=(-f "$HERE/interval-60.yml") ;;
  sampling-10) FILES+=(-f "$HERE/sampling-10.yml") ;;
  sampling-1)  FILES+=(-f "$HERE/sampling-1.yml") ;;
  batch-size)  FILES+=(-f "$HERE/batch-size.yml") ;;
  batch-delay) FILES+=(-f "$HERE/batch-delay.yml") ;;
  *) echo "모르는 변형: $VARIANT" >&2; exit 2 ;;
esac
compose() { docker compose "${FILES[@]}" "$@"; }

# 이미 떠 있는 쇼핑몰(MySQL · Redis 만 띄운 개발 중 포함)을 내려 버리지 않게, 하나라도 떠 있으면 멈춘다
if docker ps --format '{{.Names}}' | grep -q '^monimo-shop-'; then
  echo "쇼핑몰 컨테이너가 이미 떠 있다. docker compose -f docker-compose.dev.yml down 뒤 다시 실행" >&2; exit 1
fi

OUT=$(dirname "$CSV")
mkdir -p "$OUT"
[ -s "$CSV" ] || echo "time,variant,rate,secs,service,cpu_usec,mem_bytes,reqs,p95_ms,failed,recv_metrics,recv_traces" > "$CSV"

# up 이 반쯤 실패해도 띄운 것은 내린다
trap 'compose down >/dev/null 2>&1 || true' EXIT
# healthy = 부팅이 끝났다. 부팅 CPU 는 측정에 넣지 않는다
compose up -d --wait >"$OUT/compose-up.log" 2>&1 || { tail -20 "$OUT/compose-up.log" >&2; exit 1; }

k6() { # $1 = 길이, 나머지 = k6 인자. check 가 틀려도(종료 코드 99) 측정은 이어 가고, 기록에 failed 로 남긴다
  local dur=$1; shift
  docker run --rm --network monimo-dev -v "$ROOT/k6:/scripts:ro" -v "$OUT:/out" -e BASE_URL=http://gateway:8090 \
    -e RATE="$RATE" -e DURATION="$dur" "$K6_IMAGE" run --quiet "$@" /scripts/order.js >"$OUT/k6.log" 2>&1 || true
}
cgroup() { # 서비스 · CPU 누적(µs) · 메모리(바이트). cgroup v2 전제 (도커 데스크톱 · 요즘 리눅스)
  for s in $SERVICES; do
    line=$(docker exec "monimo-shop-$s-1" sh -c \
      'echo "'"$s"' $(awk "/^usage_usec /{print \$2}" /sys/fs/cgroup/cpu.stat) $(cat /sys/fs/cgroup/memory.current)"')
    [ "$(wc -w <<<"$line")" -eq 3 ] || { echo "cgroup v2 값을 못 읽었다: $line" >&2; exit 1; }
    echo "$line"
  done
}

if [ "$RATE" = 0 ]; then sleep "$WARMUP_SECS"; else k6 "${WARMUP_SECS}s" || true; fi
before=$(cgroup)
rm1=$(received metrics); rt1=$(received traces)
[ -n "$rm1" ] || echo "경고 : 수집기 받은 건수를 못 읽었다($COLLECTOR_METRICS). recv_* 열이 빈다" >&2
SUMMARY="$OUT/k6-summary.json"
rm -f "$SUMMARY"
if [ "$RATE" = 0 ]; then sleep "$SECS"; else k6 "${SECS}s" --summary-export=/out/k6-summary.json; fi
after=$(cgroup)
rm2=$(received metrics); rt2=$(received traces)
recv_metrics=""; recv_traces=""
[ -n "$rm1" ] && [ -n "$rm2" ] && recv_metrics=$((rm2 - rm1))
[ -n "$rt1" ] && [ -n "$rt2" ] && recv_traces=$((rt2 - rt1))

read -r reqs p95 failed < <(python3 - "$SUMMARY" <<'PY'
import json, sys, os
p = sys.argv[1]
if not os.path.exists(p):
    print(0, 0, 0); sys.exit()
m = json.load(open(p))["metrics"]
print(int(m["http_reqs"]["count"]), round(m["http_req_duration"]["p(95)"], 1), m["checks"]["fails"])
PY
)
rm -f "$SUMMARY"
# k6 가 아예 안 돌았거나(이미지 · 네트워크 문제) 요청이 모자라면 이 회차를 버린다. 0 이 정상처럼 쌓이지 않게
if [ "$RATE" != 0 ] && [ "$reqs" -lt $((RATE * SECS * 95 / 100)) ]; then
  echo "k6 요청 수가 모자란다($reqs). $OUT/k6.log 확인" >&2; exit 1
fi

now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
for s in $SERVICES; do
  read -r _ u1 _ <<<"$(grep "^$s " <<<"$before")"
  read -r _ u2 mem <<<"$(grep "^$s " <<<"$after")"
  echo "$now,$VARIANT,$RATE,$SECS,$s,$((u2 - u1)),$mem,$reqs,$p95,$failed,$recv_metrics,$recv_traces" | tee -a "$CSV"
done

# NMT 는 JVM 이 끝날 때 찍으므로 멈춘 뒤 로그에서 꺼낸다
if [[ "$VARIANT" == nmt-* ]]; then
  compose stop -t 30 $SERVICES >/dev/null 2>&1
  for s in $SERVICES; do
    docker logs "monimo-shop-$s-1" 2>&1 | sed -n '/^Native Memory Tracking:/,$p' > "$(dirname "$CSV")/nmt-$VARIANT-$s.txt"
  done
fi
