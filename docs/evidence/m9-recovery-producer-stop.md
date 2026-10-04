# M9 Kubernetes producer 종료 구성 요소 검증

2026-10-04, ADR0064. 종료 증거의 보수적 판정5개와 실제 클러스터7개 사례가 PASS다.
실제 검증 `20261004T025504Z-74efb678`은 별도 소유 namespace에서 실행했다.
CI와 같은 명시적 Runner image/source 인자로 재실행한
`20261004T030222Z-f8fe9a12`도7개 전체와 정리를 통과했다. 판정5개 원시 근거는
`20261004T030222Z-8410d989`다.

1. 실제 Job/VD 형태 Pod2개에서 부모 컨테이너와 각각의 자식 프로세스가 실행되는 것을 확인.
2. 잘못된 namespace UID를 변경 전에 거절하고 quota가 생기지 않음을 확인.
3. 무관한 Job을 그대로 보존하고 namespace 차단 생성을 거절.
4. 종료 지연 중15초 timeout은 BLOCKED로 끝남. 새 생성 차단과 미확인 Pod finalizer/UID를 유지.
5. 다른 복구 UUID가 기존 작업의 차단 설정을 가져가지 못함.
6. 같은 복구 UUID로 재개해 컨테이너2개 exit0과 자식2개 wait/reap 완료를 확인. 다른 finalizer 유지.
7. 실제 API server dry-run에서 새 Pod/Job 생성이 보존한 ResourceQuota에 의해 거절됨.

보고서의 quota UID가 재개 전후 같고, 종료 Pod2개를 증거로 남긴 것을 확인했다. 시험 종료 때
생성한 namespace의 UID/소유 label을 다시 확인하고 종료가 확인된 시험 finalizer만 해제하여
namespace 제거까지 완료했다. 공유 API·broker·storage의 설정/수명은 변경하지 않았다.

시험 이미지는 기존 검증 Runner digest
`sha256:72d0ad071daca317a635a65fd58152e8ea9385cdc88a02bfddb40a4dac43bed4`,
소스035eb0e다. 이미지의 Python에서 명시적 합성 부모/자식 프로그램을 실행했으며, 실제 모델·
Runner SDK 프로토콜·복원 DB 재활성화의 검증으로 확대하지 않는다.

회귀5개는 terminal phase만 있는 경우, NodeLost/ContainerStatusUnknown, 빠진 일반/init
컨테이너·실행 중 sidecar·ephemeral container, 다른 quota UID/정책/복구 ID, 이미 종료가
시작된 Pod 및 다른 소유자를 보수적으로 거절하는 판정을 다룬다.

kind CI 게이트에는 같은 source의 Runner digest와7개 실제 사례를 연결했다.
소스4f408dd의 CI37174711098은5jobs/원시22개 PASS이며 종료7개·부모2/자식2 종료 확인·
Pod/Job 생성 차단·소유 자원 정리를 직접 대조했다(`20261004T043600Z-294040c0`).
새 이미지의 GitOpsee91977·정확한 imageID/Ready/PVC/Argo Synced와 기존10파일/두PVC 보존은
`043601Z-82aa8520`/`043807Z-5d799a7c`에서 PASS다. 공유 Ingress health는 Progressing이다.
전체 제어기/Remote/broker/장치/저장소
쓰기 차단과 fence 해제·복원 DB 활성화, 전체 M9·RPO/RTO 수용은 남는다.
