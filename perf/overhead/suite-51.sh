#!/usr/bin/env bash
# `#51` 전용 묶음. `#36` 의 suite.sh 는 그대로 두고 따로 둔다 (그 이슈 기록을 다시 돌릴 일이 없다).
#
# 왜 묶음을 갈라 돌리나 : 전부 한 번에 돌리면 약 4시간 50분이고, 중간에 멈추면 아무것도 못 쓴다.
#   묶음별로 끊으면 그 묶음까지는 결론을 낼 수 있다. 묶음 안에서는 뗌(off)과 번갈아 돌려
#   기계 흔들림과 MySQL 주문 표 쌓임이 한쪽에 몰리지 않게 한다 (`#36` 이 그렇게 한 이유)
#
#   perf/overhead/suite-51.sh interval     # 전송 간격 : off · on · interval-30 · interval-60 을 10번씩 (40회차 · 약 2시간 25분)
#   perf/overhead/suite-51.sh rest         # 나머지 : off · instr-min · sampling-10 · sampling-1 · batch-size · batch-delay 를 5번씩 (30회차 · 약 1시간 50분)
#   perf/overhead/suite-51.sh check <변형> # 검산 : 초당 40건에서 off 와 그 변형을 5번씩 (10회차 · 약 36분)
#
#   셋을 다 돌리면 80회차 · 약 4시간 50분이다 (#36 기록에서 회차당 3.64분)
#
# 10회는 전송 간격 묶음에만 둔다 : `#36` 의 원인 찾기 실험이 3회 · 번갈아 안 돌림으로 못 단정한
#   크기를 재는 것이라 그 약점을 고치는 자리다. 나머지는 `#36` 본 실험과 같은 5회다
#
# 시작 전에 : monimo-backend 의 Collector 가 떠 있어야 한다. 그래야 붙인 쪽이 실제로 보내고
#   recv_metrics · recv_traces 열이 채워진다. 쇼핑몰은 내려 둔 상태에서 시작한다 (run.sh 가 직접 띄우고 내린다)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
GROUP=${1:?묶음 (interval · rest · check)}
RATE=${RATE:-20}

# 한 회차 묶음(뗌 + 붙임 변형들)을 돌리고, 반복마다 **순서를 한 칸 돌린다**.
# 그래야 뗌이 늘 먼저 오지 않고, 기계 발열과 MySQL 주문 표 쌓임이 특정 변형에 몰리지 않는다
rotate_rounds() { # $1 = 반복 횟수, $2 = 초당 건수, 나머지 = 이 묶음의 변형들 (off 포함)
  local repeat=$1 rate=$2; shift 2
  local all=("$@") n=$# i j
  for i in $(seq 1 "$repeat"); do
    for j in $(seq 0 $((n - 1))); do
      "$HERE/run.sh" "${all[$(( (j + i - 1) % n ))]}" "$rate"
    done
  done
}

case "$GROUP" in
  interval) rotate_rounds 10 "$RATE" off on interval-30 interval-60 ;;
  rest)     rotate_rounds 5  "$RATE" off instr-min sampling-10 sampling-1 batch-size batch-delay ;;
  # 검산은 이름을 여럿 받는다. 1차 70회차에서 변형끼리 전혀 안 갈렸기 때문이다(폭이 전부 겹쳤다, 2026-10-27).
  #   그래서 물을 것이 "뗌보다 싼가" 가 아니라 "그대로 둔 것(on)보다 싼가" 로 바뀌었고,
  #   on 을 대조군으로 같이 넣어야 답이 나온다 : perf/overhead/suite-51.sh check on interval-30
  check)    shift; rotate_rounds 5 40 off "${@:?검산할 변형 이름(여럿 가능)}" ;;
  *) echo "모르는 묶음: $GROUP (interval · rest · check)" >&2; exit 2 ;;
esac
