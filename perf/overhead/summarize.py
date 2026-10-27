#!/usr/bin/env python3
"""out/results.csv 를 서비스별 중앙값 표(마크다운)로 만든다. 기준은 같은 초당 건수의 off(에이전트 뗌).

  python3 perf/overhead/summarize.py [results.csv]

- 상대 증가 : (붙임 CPU 초 - 뗌 CPU 초) / 뗌 CPU 초
- 절대 증가 : 1코어 기준 %p. 측정 구간 동안 늘어난 CPU 초 / 구간 길이
- 주문당   : 네 서비스 CPU 를 더해 주문 수(초당 건수 × 초)로 나눈 ms. k6/order.js 는 주문 절반 앞에 재고 조회
             (GET /api/stock)를 하나 더 보내므로, 주문 1건 = HTTP 요청 평균 1.5건이 쇼핑몰 전체에서 쓰는 CPU
- 메모리   : 측정 끝 cgroup memory.current 차이
- 받은 건수 : 수집기가 그 구간에 받은 데이터 포인트 · 스팬 개수(recv_metrics · recv_traces).
             보낸 "횟수" 가 아니다. 한 번에 몇 건이 오는지(P)로 나눠야 횟수가 되고,
             P 가 회차마다 같은지를 이 표로 확인한다 (#51)
"""
import csv
import os
import sys
from collections import Counter, defaultdict
from statistics import median

SERVICES = ["gateway", "order", "payment", "inventory"]
path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "out", "results.csv")

runs = defaultdict(lambda: defaultdict(list))  # (variant, rate) -> service -> [(cpu_s, mem_mb, secs)]
recv = defaultdict(list)                       # (variant, rate) -> [(recv_metrics, recv_traces)]
totals = defaultdict(list)                     # (variant, rate) -> [(total_cpu_s, reqs, p95, failed)]
by_run = defaultdict(dict)
with open(path) as f:
    rows = list(csv.DictReader(f))
for r in rows:
    if int(r["rate"]) > 0 and int(r["reqs"]) == 0:  # k6 가 안 돈 회차 (예전 run.sh 가 남긴 것)
        print(f"경고 : 요청 0 인 회차를 뺀다 ({r['time']} {r['variant']} {r['service']})", file=sys.stderr)
        continue
    key = (r["variant"], int(r["rate"]))
    cpu = int(r["cpu_usec"]) / 1e6
    runs[key][r["service"]].append((cpu, int(r["mem_bytes"]) / 1048576, int(r["secs"])))
    orders = int(r["rate"]) * int(r["secs"])
    by_run[(r["time"], key)][r["service"]] = (cpu, orders, float(r["p95_ms"]), int(r["failed"]))
    if r["service"] == SERVICES[0]:  # 회차마다 한 번만 (네 서비스 행에 같은 값이 들어 있다)
        recv[key].append((r.get("recv_metrics") or "", r.get("recv_traces") or ""))
for (_, key), svc in by_run.items():
    orders, p95, failed = next(iter(svc.values()))[1:]
    totals[key].append((sum(v[0] for v in svc.values()), orders, p95, failed))


def med(key, service, i):
    return median(x[i] for x in runs[key][service])


for variant, rate in sorted(runs, key=lambda k: (k[1], k[0] != "on", k[0])):
    if variant == "off" or (variant.startswith("nmt")):
        continue
    base = ("off", rate)
    if base not in runs:
        print(f"경고 : {variant} 초당 {rate}건과 비교할 off 회차가 없다", file=sys.stderr)
        continue
    n, nb = len(totals[(variant, rate)]), len(totals[base])
    print(f"\n### {variant} vs off · 초당 {rate}건 (중앙값, {variant} {n}회 · off {nb}회)\n")
    print("| 서비스 | 붙임 CPU | 뗌 CPU | 상대 증가 | 절대 증가 (1코어) | 메모리 붙임 | 메모리 뗌 | 메모리 차이 |")
    print("|---|---|---|---|---|---|---|---|")
    for s in SERVICES:
        c1, c0 = med((variant, rate), s, 0), med(base, s, 0)
        m1, m0 = med((variant, rate), s, 1), med(base, s, 1)
        secs = median(x[2] for x in runs[base][s])
        print(f"| {s} | {c1:.2f}초 | {c0:.2f}초 | {(c1 - c0) / c0 * 100:+.0f}% | {(c1 - c0) / secs * 100:+.1f}%p "
              f"| {m1:.0f}MB | {m0:.0f}MB | {m1 - m0:+.0f}MB |")
    if rate:
        t1 = median(t / q * 1000 for t, q, _, _ in totals[(variant, rate)] if q)
        t0 = median(t / q * 1000 for t, q, _, _ in totals[base] if q)
        p1 = median(p for _, _, p, _ in totals[(variant, rate)])
        p0 = median(p for _, _, p, _ in totals[base])
        f = sum(x for *_, x in totals[(variant, rate)]) + sum(x for *_, x in totals[base])
        print(f"\n주문 1건당 쇼핑몰 전체 CPU (재고 조회 포함) : {t1:.2f}ms vs {t0:.2f}ms ({t1 - t0:+.2f}ms, {(t1 - t0) / t0 * 100:+.0f}%) · "
              f"k6 p95 {p1}ms vs {p0}ms · 실패한 check {f}건")

# 받은 건수 : 회차마다 몇 건이 왔나. 값이 한 가지면 치우친 것이고, 두 가지가 섞이면 흔들린 것이다 (#51)
rows_with_recv = [k for k in sorted(recv) if any(a or b for a, b in recv[k])]
if rows_with_recv:
    print("\n### 수집기가 받은 건수 (보낸 횟수가 아니라 건수다)\n")
    print("| 변형 | 초당 | 회차 | recv_metrics 값 (회차 수) | recv_traces 중앙값 |")
    print("|---|---|---|---|---|")
    for variant, rate in rows_with_recv:
        got = recv[(variant, rate)]
        ms = Counter(int(a) for a, _ in got if a != "")
        tr = [int(b) for _, b in got if b != ""]
        shape = " · ".join(f"{v}({n}회)" for v, n in sorted(ms.items())) or "없음"
        trs = f"{median(tr):.0f}" if tr else "없음"
        print(f"| {variant} | {rate} | {len(got)} | {shape} | {trs} |")
    print("\n- recv_metrics 가 한 값으로 몰리면 창 안의 전송 횟수가 **치우친 것**이다 (research.md 2.6.2)")
    print("- 두 값이 섞이면 그 차이가 **전송 1번에 오는 건수 P** 다. P 가 간격마다 같은지가 5절 A 전제 ① 이다")
    print("- 뗌(off) 회차의 값은 쇼핑몰이 아닌 다른 발신자의 몫이라 **배경값**으로 본다")
