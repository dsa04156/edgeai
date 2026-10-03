# M7 작업별 최초 배치 검증

2026-10-04. ADR0053/V29. Run 기본값 위에 DAG 작업 키별 AUTO/NODE 최초 배치를 연결했다.
이 문서는 AUTO/NODE 배치 검증 기록이다. BATCH VD/REMOTE 후속은
[혼합 배치 근거](m7-mixed-task-targets.md)를 따른다. STREAM VD/REMOTE와 외부 장치 수용은 남으며 M7 전체 완료가 아니다.

## 후속 CI·배포 확인

`fe32ed8a0409813976926a2fa6c6a901f39f0459`의 CI37149032705는5jobs 모두 성공했다.
원시17개 PASS·PG212·Runner111/MQTT90·kind22Run/S320·VD4/S35와 STREAM11개/Pod39개/
고정 S3결과24개, 영속 TLS broker 교체·저장소·배포 데모를 확인했다.
원시 감사는 `20261003T202950Z-64c51a17`이다. 새 혼합 V30 코드는 이 CI에 포함되지 않는다.

GitOps `aabb81582cb6344e33d3508bedfb813d9fc9d9b8`의 실제 API/dashboard/MinIO imageID·
Ready/PVCBound/ArgoSynced를 `20261003T202950Z-98cfa5e7`에서 확인했다.
공유 개발 DB는 migration29개 모두 성공했고 V29 checksum1421541522다.
기존10파일의 고정 버전·내용과 PostgreSQL/MinIO 두PVC 신원을
`20261003T203013Z-192106b1` 저장소 검사에서 보존했다.
Argo aggregate health의 기존 공유Ingress Progressing은 유지한다.

이 CI에서 자동 취소도 통과했으나 아래 선행204645f 실패의 원인이 밝혀진 것은 아니다.
실패 진단을 artifact에 보존하는 개선을 유지한다.

## 동작과 검증 경계

`taskExecutions`는 발행된 DAG의 작업 키만 받으며 Run/Task 생성과 함께 원자적으로 고정한다.
UUID 대소문자·객체 순서를 정규화하고 빈 객체와 생략은 같은 멱등 요청이다. 같은 키의 배치 변경은
409, 잘못된 작업 키400, 없는 노드404, VD/REMOTE Run의 덮어쓰기는409다.

대기 중인 Task에도 `initialMode`/`initialNodeId`를 저장한다. 최초 root·BATCH 하위·STREAM 그룹은
이 위치를 사용한다. 재시도·그룹 복구·최종 처리 복구는 직전 Attempt의 실제 위치를 계승하며,
offload 후에는 최초 위치로 되돌아가지 않는다. DB는 불변 계획/Task 신원, 노드 FK와 다른 최초
Attempt 배치를 거절한다. 기존 V1–V28은 변경하지 않았다.

## 실행 근거

원시 결과는 무시된 `docs/evidence/runs/<runId>/`에 보관한다.

| 검증 | runId | 결과와 범위 |
|---|---|---|
| 공개 요청·DB 계획·동시 생성 | 20261003T191041Z-f84e6286 | PASS, Workflow10개; 실제 PG/MVC, runtime 비활성 |
| BATCH 재시도/결과 해제·STREAM 배치 복구 | 20261003T191332Z-ace5fd8a | PASS, Retry10/Stream31; Pod 신원·S3 receipt·broker 경계는 fixture |
| PostgreSQL 전체 | 20261003T192019Z-985ae978 | PASS,212개, 실패/오류/skip0 |
| 실제 저장소 전체 | 20261003T192321Z-167ceef8 | PASS,38개; 실제 Spring/PG/S3/TLS MQTT/Runner, 해당 시험의 Pod 경계는 fixture |
| 서버 단위 | 20261003T192544Z-f845d884 | PASS,105개, 실패/오류/skip0 |
| OpenAPI·타입 생성·포장 Swagger·MVC | 20261003T191712Z-251284bd | PASS,5개 계약·MVC26개 |
| 화면 lint/type/build·PC/모바일 | 20261003T192322Z-f6f20ca2 | PASS,42개; HTTP fixture |
| 실제 API/DB·PC/모바일·Swagger | 20261003T192636Z-c6489bc4 | PASS,10개; 공개 taskExecutions 생성/재전송·대기 Task 표시·실제 Swagger 설명/CSRF |
| 기존 로컬 DB 업그레이드 | 20261003T192822Z-b237dee1 | PASS,V29 및 전체29개 migration 성공; 기존 Task9,335개 존재, 기본 배치 불일치0 |
| 실제 Kubernetes 새 배치2개 | 20261003T191955Z-83e37cad | PASS,Runner Pod8개·고정 S3결과6개·고유 소유 자원 정리 |
| 실제 Kubernetes 전체11개 | 20261003T192206Z-e77aa57a | PASS,Runner Pod39개·고정 S3결과24개·API 교체13.332초·소유 자원/Job 장벽 정리 |
| 모바일 배치 입력 시각 확인 | 20261003T192939Z-3f49d06a | PASS,PC/모바일2개; 좁은 화면에서 정책·노드 입력을 세로 배치 |
| 자동 취소 단독·최종 진단 경로 | 20261003T193548Z-8aef1cde | PASS,실제 Pod2개·새 Attempt 없음·Result0·소유 자원 정리 |

실제 클러스터의 새 `placement`, `placement-recover`는 AUTO Run에서 root를 노드A, sink와
BATCH report를 노드B에 고정한다. 서로 다른 노드 UID와 실제 Pod 생산자를 대조하고, root/sink
상태9에서 sink Job을 제거해 전체 이전 runtime 종료·권한 회수와 새 Attempt2개를 확인한다.
복구된 두 작업도 각 최초 노드를 유지하며 동일 Device owner/센서 커서로 재연결한다.
root14/sink23/report37의 실제 S3 고정 버전·bytes·SHA를 확인했다. BATCH report는 두 결과가
확정되기 전에는 Attempt가 없고, 해제 뒤 지정한 노드B에서 실행됐다.

API는 현재 소스 JAR `b107ac665ec63aed233b63119c401624740a273ae7ee7781937195b9e1e62023`,
Runner는 CI에서 이미 검증한638d77d의 digest `eabb42b73b41b4768550ac65df2cfc783fde013cb6dd8c46157488e672502376`이다.
새 API 게시 이미지/CI/개발 배포 수용과 이 로컬 JAR 시험을 구분한다. 합성 데이터와 참조 모델이며
실장비 정확도 또는 성능 수용을 대신하지 않는다.

V29의 최초 적용 후 SHA-256은 `eacf25681b73403f7ed79cc04c6fd324207ff6bbd9187ae648262572273992dd`,
로컬 Flyway checksum은1421541522다. 공유 개발 DB 적용은 위 후속 배포에서 확인했다.

## 실패와 남은 CI 게이트

- `191711Z-8a37fb12`: 기존40개 UI 통과, 새2개는 select label을 찾는 시험 selector에서 실패했다.
  접근성 snapshot의 combobox 이름으로 고쳤다. `192018Z-8b188f38`은 Next.js 알림까지 함께
  선택해 실패했으며 main 내부로 한정했다. 새 fixture의 Result 응답도 실제 `items` 계약으로
  맞췄다. `192058Z-31660222` 새2개와 최종42개를 통과했다.
- `192449Z-a6a751c8`: 실제 브라우저8개 통과, Swagger2개는 설명과 요청 schema 두 영역이
  함께 선택돼 실패했다. Run 설명 영역으로 한정한 뒤 최종10개를 통과했다.
- 선행204645f의 CI37145780408은 scaffold/runner/storage 성공, images 실패, gitops skipped다.
  원시17개 중16PASS/1FAIL이며 STREAM 자동 취소의 draining 이후 driver 실패가 확인됐다.
  해당 CI는 상세 driver 위치/진단 파일을 artifact에 포함하지 않았다. 원인은 미확정이다.
  로컬 전체11개 성공만으로 이 실패가 해결됐다고 판정하지 않는다. 명시적 report 경로에
  실패 상태와 비밀값을 제외한 phase/type/code locations를 보존하고 다음 CI에서 확인한다.
- `193148Z-e21423e2`: 자동 취소 자체와 소유 자원 정리는 통과했으나 취소만 선택한 진단에서
  빈 artifact 목록을 다운로드 검증기로 보내 최종 판정이 실패했다. 전체 성공 시나리오의 파일 검증은
  유지하고, 취소만 선택하면 모든 시나리오 취소와 실제 DB Result0을 확인하도록 수정했다.
  `193548Z-8aef1cde`에서 같은 단독 진단을 통과했다. 선행 CI driver 실패와는 다른 문제다.

CI 기본 STREAM 목록11개와 해당 이미지·배포는 위 후속 확인을 통과했다.
선행 CI 간헐 실패의 원인 확인, 후속 혼합 배치·실제 외부 장치 및 M5 잔여/M8–M10은 계속 진행한다.
