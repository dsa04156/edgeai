# M7 완료 허가 뒤 새 Attempt 복구

2026-10-03 KST. [ADR0045](../adr/0045-stream-finalizer-attempt-recovery.md), V26.
M7 전체 완료 또는 공개 retry 활성화 판정은 아니다.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 초기 완료 서버/그룹 PG 회귀 | 20261003T123648Z-4f097ff4 | PASS/0, 31개 |
| checkpoint HTTP client·원본 actor 읽기/현재 actor 쓰기 분리 | 20261003T123812Z-873c79f7 | PASS/0, 15개 |
| 실제 Runner 종료·빈 폴더·broker 정지·새 Attempt·취소 | 20261003T123832Z-7c769eb3 | PASS/0, 15개 |
| 실제 Spring/PG/S3/TLS MQTT·새 Attempt 결과14·기존 DAG | 20261003T124227Z-62e08c4d | PASS/0, 6개 |
| 전체 PostgreSQL·위조 인계/회수 장벽 추가 | 20261003T124352Z-4747dbfb | PASS/0, 187개·실패/skip0 |
| 전체 Runner/SDK | 20261003T124353Z-3ca946b9 | PASS/0, 111개·88.630초 |
| 전체 실제 S3/DB/Remote/VD/stream | 20261003T124819Z-1b872ab7 | PASS/0, 36개·실패/skip0 |
| 서버 단위/MVC | 20261003T125018Z-335cd458 | PASS/0, 101개·실패/skip0 |
| OpenAPI 타입·배포 리소스 원본 일치·MVC/Swagger | 20261003T125054Z-a37f68d0 | PASS/0 |
| 실제 API/DB·PC/모바일·Swagger | 20261003T125157Z-04453f34 | PASS/0, 10개·55.3초 |
| 진단 추가 후 재연결 단독12회 | 20261003T125000Z-adfc645e | PASS/0, 12회·128.269초, 간헐 실패 미재현 |
| 진단 추가 후 전체 HTTPS/TLS MQTT | 20261003T125432Z-ca2a1c1b | PASS/0, 73개·129.483초, 근본 원인 미확정 |

프로젝트 DB V26 적용 success/checksum662996587을 확인했다. V26 파일 SHA-256은
`dfa815b7635d277201a75330e9d11fb92af97359cd187ab4405118e572a9b163`이며 이후 불변이다.
V1–V25는934d003 기준 bytes 그대로이고 V25 DB checksum은 기존-1766729663이다.

## 확인한 경계

- 완료 허가 후에는 실패한 Task만 재시도한다. peer의 이미 확정된 Result, 독립 실행,
  BATCH 하위 작업의 대기를 보존하고 새 최종 Result 확정 후에만 하위를 배정한다.
- 동일 Task의 이전 Runtime 물리 종료, 미완료 CREATE 해소, 관련 broker 권한 CLOSED가 모두
  필요하다. 여러 retry worker 중 하나만 새 Attempt를 만든다. 두 차례 인계도 최초 grant와
  checkpoint를 그대로 가리키며 새 route generation/checkpoint/completion report를 만들지 않는다.
- DB가 살아 있는 producer·미완료 CREATE·미회수 경로·다른 Task 허가 인계·이력 수정을 거절한다.
  취소·예산 소진·다른 checkpoint·옛 producer는 결과 확정 권한을 얻지 못한다.
- SDK 일반 latest/upload/commit/handover는 이전 actor receipt를 거절한다. 명시적인 FINALIZE
  응답의 checkpointActor만 원본 고정 checkpoint 읽기에 사용하며 요청은 새 Attempt로 인증한다.
- 실제 Runner 프로세스를 허가 후 종료하고 broker를 정지한 시험에서 새 Attempt/빈 폴더가
  MQTT/모델/journal 없이 상태14를 읽고 최종 파일을 한 번 확정한다. S3 읽기 중 취소는 이를 막는다.

## 실제 서버와 fixture 구분

`StreamSourceCompletionIntegrationTest`의 새 시험은 실제 Spring HTTPS·격리 PostgreSQL·
versioned MinIO·TLS Mosquitto·DeviceSource 두 개·Session 모델로4,2와5,3→14를 계산한다.
공동 허가 뒤 실제 Session/DeviceSource를 닫고 RUNTIME_LOST 관측을 주입한다. 실제 broker worker가
권한을 회수하고 DB가 새 Attempt/epoch2와 인계 이력을 만든 뒤, 빈 폴더의 독립 Runner가 실제
claim/FINALIZE/finalized/S3/Result API를 수행한다. 원래 checkpoint ID·grant time·경로 세대1은
그대로이고 Result는 새 Attempt에 귀속된다. 고정 version의 S3 결과 bytes를 읽어 값14를 대조한다.

Run 정책 생성과 Pod provisioning/identity/물리 종료 관측은 명시적 fixture다. 원래 계산은 Session
프로세스이며 실제 Kubernetes Runner Pod를 죽인 시험은 아니다. 별도 Runner 프로세스 시험은
실제 SIGKILL/정지 broker를 사용하되 제어 서버·저장소 응답은 HTTPS fixture다.

## 실패와 남은 위험

- `123554Z-cbab4107`: PG 시험의 mocked authority worker 때문에 성공한 source의 Device 경로를
  닫지 않은 fixture가 전체 회수 assertion에서 실패했다. source 경로 종료를 명시한 후 통과했다.
- `124046Z-172e42d0`: wait helper가 성공 후 조건을 다시 평가해 일회성 retry 반환값을 false로
  보았다. 후속 Attempt epoch라는 영속 결과를 검사하도록 수정하고 실제 연결6개를 통과했다.
- `124603Z-5125f886`: V26 외래키가 기존 TRUNCATE negative test를 immutable trigger 이전에
  SQLSTATE0A000으로 막았다. 모든 참조 테이블을 포함해 의도한 무결성 거절을 확인한 뒤36개 통과했다.
- 전체 MQTT73개 `124604Z-d3aec68d` 중 기존 느린 소비자/broker SIGKILL 재연결1개가20초 진행
  제한에 걸렸다. 변경한 finalizer15개는 통과했다. 앞선 동일 증상과 같이 원인은 아직 미확정이다.
  실패 시 페이로드 없이 연결/큐 수·처리 순번을 남기도록 진단을 추가했다. 단순 재실행 성공으로
  이 문제를 해결했다고 판단하지 않는다.

공개 STREAM retry·Device 자동 재연결·실제 Kubernetes 그룹/최종 처리 장애 수용은 남는다.
새 V26 코드의 CI·이미지 배포 검증과 선행934d003 CI를 구분한다. M5 잔여/M7–M10은 미완료다.

## 후속 CI 확인

source `a0fc80231eb54a2ae1d4ca7c1fc3c9abf417c627`의
[CI37124689963](https://github.com/dsa04156/edgeai/actions/runs/37124689963)은5jobs 성공이다.
내려받은 결과JSON17개 모두 PASS/0이며 runner/storage/scaffold 및 실제 kind 결과를 확인했다.
완성 API 이미지 자체의 STREAM AUTO/NODE/취소3개, Device 인증 조회 각2개, 실제 Runner Pod8개,
API 교체16.209초 동안 동일 Runner Pod2개 유지, 고정 S3 결과6개·14/23/37을 검증했다.
실제 그룹 또는 최종 처리 Pod 장애 주입 시험으로 확대하지 않는다.
원시 kind 보고서·기존 원본 result/log는 `docs/evidence/runs/20261003T131317Z-f6c2aa87`에 보존했다.
Runner digest는 `sha256:e7b78cbc17edd9adc0e3b9ad9b9a94fe0948294b4763190d8598844fe1ffb592`다.
GitOps `77bef5b93d0e38ec575490b54b68a6e542b156f8`가 이 검증 이미지들을 고정했다.
기존 클러스터 `20261003T133607Z-3eec4778`에서 정확한 API/Dashboard/MinIO imageID3개,
Ready·PVC Bound·Argo Synced·VD 활성화를 대조했다. 공유 Ingress 상태 제한에 따른 aggregate
health Progressing은 유지한다. 전용 Argo Application만 새 Git revision으로 refresh했다.
