# 통합 CI의 작업 시간 제한 도달

## 2026-10-05: 모든 검사 완료 후 이미지 발행 단계의90분 제한

소스 `931922aa5afead039b2e5bcc14dc1128bdd30c7d`의
[CI37256444752](https://github.com/dsa04156/edgeai/actions/runs/37256444752)는5개 job 성공,
images 취소, gitops 생략으로 종료됐다. 원시 result.json45개는 모두 PASS이며 전체 kind와
소유 클러스터 정리도 통과했다. 복원 실행 정리91·Kubernetes/VD Result20/24·STREAM
Result30/34·상속 finalizer35/39·STREAM workflow55개를 실제 보고서에서 대조했다.
이 소스는 새 V37/V38과 혼합그룹27개를 포함하지 않는다.

images job 시작은03:01:57Z, 이미지 push 시작은04:31:52Z, 취소는04:32:08Z다.
check111598316604의 annotation이 **1h30m0s 최대 실행 시간 초과**를 명시한다.
로그에는 API/dashboard push digest가 찍혔지만 job 출력 확정과 GitOps는 실행되지 않았다.
registry와 실제 배포는 별도로 확인하지 않았으므로 배포 성공으로 판정하지 않는다.

증거는 `.tools/ci-37256444752-terminal-audit.json`, 기존35개 원시 자료
`.tools/ci-37256444752-partial/`, 추가10개
`.tools/ci-37256444752-terminal-images/`와 private 전체 로그에 보존했다.
추가 혼합그룹 검사와 발행 여유를 포함해 전체 job 제한을120분으로 조정했다.
개별 명령 timeout·검사 목록·실패 판정은 유지한다. 새 원격 CI와 실제 배포 검증은 후속이다.

## 선행55분 제한 사례

소스 `8b964d6f70b54f76da65c4366d249b2cb2075867`의
[CI37234177387](https://github.com/dsa04156/edgeai/actions/runs/37234177387)은
5개 job 성공, images 취소, gitops 생략으로 끝났다. 새 API/dashboard 이미지 발행·배포는
실행하지 않았다. 실행 중 상태를 최종 완료 상태로 대체해 기록한다.

images job은 `2026-10-04T21:16:50Z`에 시작해 `22:12:14Z`에 종료됐다.
GitHub check `111533510374`의 annotation은 **55분 최대 실행 시간 초과**를 명시한다.
로그의 취소 오류는 `22:12:03Z`다. 그 전 실제 Kubernetes 실행·STREAM24개·혼합 Remote3개,
복원 실행 정리90개는 통과했다. 마지막 STREAM 복구는44개 중42개 뒤 FAIL/정리 오류를
기록했다. 전체 kind의 최종 result.json과 배포 데모 보고서는 없으므로 전체 성공이나
모든 소유 자원 정리 완료로 판정하지 않는다. 테스트 runner 자체는 폐기되는 GitHub 환경이다.

다운로드한 원시 result.json38개 중37개 PASS,1개 STREAM 복구 FAIL이다. `images` 컨테이너
검사는 `211830Z-16762501`, 복원 실행 정리는 `215831Z-da1f63ae` PASS이고,
`220453Z-af0fda1d`는 종료되지 못한 STREAM 복구 보고서다. 보존 위치는
`.tools/ci-37234177387-audit/`다. 실패 시점과 job 제한은 확인했으나 STREAM의 별도 내부
오류가 없었다는 주장까지 확대하지 않는다.

실행 내용이 늘어난 전체 images job의 제한을55분에서90분으로 늘렸다. 개별 명령의
timeout·상태 판정·정리 검사는 유지하고 새 Kubernetes Result 복구20개를 포함한다.
검사를 생략하거나 실패를 허용하지 않는다. 다음 CI의 끝까지 실행·이미지 발행·GitOps
배포 및 기존 데이터 보존이 확인될 때까지 시간 제한 문제의 수용 완료는 보류한다.
