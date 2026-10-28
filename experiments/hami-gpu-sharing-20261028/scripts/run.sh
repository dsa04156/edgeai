#!/bin/bash
# usage: run.sh <name> <pods> <gpucores> <gpumem MiB, 0=제한 없음>
# 모든 Pod가 같은 시각(START_AT)에 측정을 시작하고 60초 뒤 결과를 results/<name>.jsonl에 저장한다.
S=$(cd "$(dirname "$0")" && pwd); R=$S/../results; name=$1
mkdir -p $R
kubectl create ns hami-bench --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label ns hami-bench hami.io/webhook=enabled --overwrite >/dev/null
kubectl -n hami-bench create configmap llm-bench --from-file=$S/bench_llm.py --dry-run=client -o yaml | kubectl apply -f - >/dev/null
START=$(( $(date +%s) + 90 ))
python3 $S/gen.py "$@" $START | kubectl apply -f - >/dev/null
for p in $(kubectl -n hami-bench get pod -l exp=$name -o name); do
  kubectl -n hami-bench wait --for=jsonpath='{.status.phase}'=Succeeded $p --timeout=300s >/dev/null || kubectl -n hami-bench describe $p | tail -15
done
for p in $(kubectl -n hami-bench get pod -l exp=$name -o name); do kubectl -n hami-bench logs $p | grep '^RESULT' | sed 's/^RESULT //'; done > $R/$name.jsonl
echo "$name start=$START end=$((START+60))" >> $R/windows.txt
cat $R/$name.jsonl
kubectl -n hami-bench delete pod -l exp=$name --wait=true >/dev/null
