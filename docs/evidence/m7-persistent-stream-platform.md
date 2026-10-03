# M7 영속 TLS 스트리밍 기반 준비

2026-10-04 KST. [ADR0049](../adr/0049-persistent-stream-platform.md).

| 검사 | 실행 ID | 확인 |
|---|---|---|
| 실제 Kubernetes 신원 최초 준비 | 20261003T145743Z-313d7867 | 불변 Secret4·공개 ConfigMap3, 복구본0600 |
| 실제 설치 재실행·복구 및 충돌 거절 | 20261003T150957Z-e0409c0b | 6개 PASS/0, 기존7개 UID/resourceVersion/data 불변 |
| 실제 영속 TLS broker·Pod 교체 | 20261003T150957Z-47d2a3f7 | PASS/0, 서로 다른 Pod UID·동일 PVC·역할/기본 deny 보존 |
| 선택 컴포넌트 전체 렌더링·server dry-run | 직접 Kubernetes 검사 | API에 HTTP+HTTPS, MinIO HTTPS probe, 기존 PVC 규격 유지 |

신원 시험은 같은 bundle 재실행, 모든 Secret에서 새 로컬 bundle 복구, 바뀐 principal key,
다른 namespace UID, 노출된0644 파일, symlink의 거절을 포함한다. 최신 코드에서 복원된 CA/
서비스 인증서의 개인 키·서명·hostname·유효기간·서로 다른 키4개도 확인한다.
선행 동일 범위의 시험은150717Z-36686843이며, 검증 강화 후 위6개를 다시 실행했다.

브로커는 실제 digest `sha256:38c0da4f2ef84284d47b3b3eeea1cb3bdeabe81ee10caf0cd5c5ff61ee3ea408`를
확인한다. 신뢰 CA와 hostname을 검증한 TLS, 실제 관리 비밀번호, 익명/틀린 비밀번호와 공인 CA만
신뢰한 접속의 거절을 확인했다. 고유한 빈 시험 역할을 만든 뒤 실제 Pod를 UID 조건으로 삭제하고,
동일 PVC에서 재기동한 새 Pod가 같은 역할을 반환함을 검사했다. 시험 역할은 제거했다.
선행150717Z-8b88f870에 실제 부정 인증/imageID 확인을 보강한 후 위시험을 다시 실행했다.
원시 보고서는 해당 실행 폴더의 `broker.json`이다. 당시 실제 배포의 STREAM은 비활성이었으며
후속 ADR0050에서 활성화했다. 새 CI·배포 판정은 아래를 따른다.

## 작업 중 오류와 정리

첫 준비 도구는 server dry-run이 돌려준 임시 Service IP를 생성 입력에 재사용해 주소 할당이
거절됐다. 원본 매니페스트를 client 렌더링으로 읽는 방식으로 수정했다. 해당 명령의 여러 JSON
객체 출력도 단일 List로 가정하지 않고 각각 파싱했다.

시험용 상위 overlay가 namespace를 지정하지 않아 새 broker Service/StatefulSet/PVC/ConfigMap이
default에 생성된 오류도 확인했다. Secret이 없어 broker 컨테이너는 시작하지 않았다. 이번에
생성한 자원의 UID·생성 시각·Pod owner를 검증한 후 UID 조건으로 정리했고 Pod 포함5개가
default에서 모두 사라짐을 확인했다. broker 리소스/ConfigMap namespace와 생성 명령의 namespace를
edgeai로 명시하고, 렌더링된 모든 대상의 namespace를 먼저 검사한 뒤 실제 시험을 통과했다.
기존 API·DB·MinIO·사용자 데이터를 변경하지 않았다.

## CI·활성화 범위

새 kind 게이트에 신원 재실행/복구6개와 실제 영속 broker Pod 교체를 추가하고 공개 broker 보고서를
artifact에 포함한다. 이 CI 변경은 아직 해당 커밋으로 실행하지 않았다. API/MinIO TLS 패치의 실제
배포 활성화·장치 데모·외부 연결 및 M5 잔여/M7–M10 전체 수용은 미완료다.

위 문단은 최초 준비 당시 상태다. 후속d3797d6의 CI37137184323은5jobs/원시JSON17개 모두
PASS/0이며171024Z-5237e391에서 직접 감사했다. 새 kind의 신원 복구6개·영속 broker의
새 Pod/동일PVC/역할·기본 deny와 CA/잘못된 인증 거절이 통과했다. 실제 MinIO TLS 컴포넌트
256KiB 왕복/익명403과 해당 배포의 다중 장치 데모3개/8Pods/S36개도 통과했다.
GitOps4263ecb의 실제 API/dashboard/MinIO imageID·Ready·PVCBound·ArgoSynced는
171025Z-f7b61da9, 기존10파일/두PVC 보존은171301Z-f84b93cb에서 확인했다.
외부 장비·운영 보강과 M5 잔여/M7–M10 전체 수용은 여전히 남는다.

선행 공개 retry 소스a29f6c9의 CI37129406733은5jobs·원시JSON17개 모두PASS/0이다. 실제 포장 API/
Runner 이미지의 kind5개/AUTO·NODE·그룹·최종 처리 복구·취소, Pod17개·S3파일12개·API 교체11.265초,
Runner111개·TLS MQTT87개를 직접 확인했다. GitOps c07b273의 이미지가 적용된 실제3imageID·Ready·
PVCBound·ArgoSynced는150149Z-968d709a에서 확인했다. 당시 sync revision은후속533d850이며
aggregate health는 기존 공유 Ingress 때문에Progressing이다.

ADR0048 HTTPS 코드는 GitOps 위에 rebase한533d8504630b185529156302fe37e55df0e9717f로 푸시했다.
CI37131722732는별도 진행 중이며 이 새 기반 준비의 CI/운영 수용과 구분한다.
