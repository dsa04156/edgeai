# Native Runner 통합 CI의 S3 복제 대기 실패

소스 `60c8be3e5bd0970ea83d15941ba3ec6761b8a240`의
[CI37223827513](https://github.com/dsa04156/edgeai/actions/runs/37223827513)은
5개 job 성공, images 실패, gitops 생략으로 끝났다. API/dashboard 발행 및 새 GitOps 배포를
성공으로 판정하지 않는다. Runner native 두 이미지/index 발행은 앞 단계에서 완료됐다.

다운로드한39개 원시 결과 중37개가 PASS다. 실패는 `kind`와 그 안의
`recovery-stream-workflows`다. 감사 `20261004T193318Z-cfe2641f`가 이 실패 범위와
해당 소스의 실패 줄을 직접 대조했다. 감사 PASS는 CI 성공을 뜻하지 않는다.

`20261004T192632Z-e6c810ca`는 업무 복구 case0개 상태에서 TimeoutError로 끝났다.
호출 위치는 test-recovery-stream-workflows.py:196 → recovery_device_test_support.py:106 →
해당 소스 storage_backup.py:207이며, 캡처한 원래 S3 version이 대상에 도착하기를 기다리던
120초 제한이다. API·브로커·스토리지·연결·DB·namespace 정리와 소유 kind cluster 제거를
확인했다. 실패에 도달하기 전 실제 STREAM24개/Node43·VD33Pods/고정S354개,
혼합 복구69개/복원DB6/부모자식5쌍은 통과했다. 뒤의 영속 배포 데모는 실행하지 않았다.

이 실행에는 ADR0098의 명시적 소유 target resync가 없다. 실패 위치는 앞선 로컬 결합
시험 두 번과 같으며, 별도 느린 scanner 재현·수정 후 백업11개 및 결합44개는
[로컬 검증](m9-storage-explicit-resync.md)을 통과했다. 각 실패의 내부 scanner 경로까지
관측한 것은 아니다. 다음 소스에 이 수정과 ADR0097/0099를 포함해 전체 CI를 다시 확인한다.
기존 배포 및 기존 고정 파일/PVC 검증 근거를 새 배포 성공으로 대체하지 않는다.
