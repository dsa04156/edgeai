# 통합 CI의 작업 시간 제한 도달

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
