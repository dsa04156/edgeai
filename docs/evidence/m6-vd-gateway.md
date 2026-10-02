# M6 — 실제 VD Pod gateway와 영속 worker

ADR0016의 `KubernetesVDGateway`, `VDWorker`, `VDTokenService`와 opt-in 설정을 추가했다.
공개 등록은 자동 실행하지 않으며 `EDGEAI_VD_ENABLED=false`가 기본값이다.
이번 검증은 Pod 경계와 명령 worker의 검증이며 실제 VD Task/Result 종단 완료는 아니다.

## 검증한 경계

- HTTP 장애 fixture5개: POST 응답 유실 후 같은 Pod 재사용, 다른 claim/intent 거절,
  audience·Pod-bound 신원·owner·Running/Ready 구분, 과거 UID와 다른 늦은 Pod의 정확한 소유
  관계 확인 및 UID precondition 삭제, namespace/generation/owner 불일치 시 삭제 금지,
  페이지 간 resourceVersion 일치와 HTTP/event410 후 relist 반환.
- HMAC2개: 서비스 객체 재생성 후 동일 자격 검증, namespace/VD/runtime/generation/nonce
  변경 시 거절, 다른 key·Task 접두사 거절, 잘못된 key 파일의 비밀/경로 비노출.
- 실제 PostgreSQL lifecycle13개 중 worker 신규4개: 생성 응답 유실·다른 worker 재개,
  Pod/Secret 부재 전 binding 종료 금지, Kubernetes 장애 중 startup 만료·실행 생성 차단,
  명령 lease 만료 뒤 도착한 생성 응답 및 다른 worker의 확정, Ready 관측 상실과 종료 이력의
  늦은 Pod 정리 재개. 이 시험에서 Kubernetes gateway와 Pod 신원은 fixture다.
- 실제 Kubernetes VD3개: AUTO·NODE scheduler 배치와 실제 Node UID, 불변 Secret의 owner UID,
  Pod-bound TokenReview 및 별도 Ready 변화, unbound SA token·다른 audience·다른 Pod UID/
  generation 거절, list/watch, STOPPED와 UID 삭제, 실제 Pod를 다시 생성해 다른 UID가 된
  늦은 실행의 정리. ownerReferences를 제거한 살아 있는 Pod는 삭제를 거절하고 fixture가 소유
  관계를 복원한 뒤 정리한다. 기존 실제 Job2개도 같은 실행에서 회귀 검증했다.

실제 Kubernetes gateway는 명시적 context `kubernetes-admin@kubernetes`와 소유 namespace를
확인한 뒤 발급한10분 control-plane SA token/CA로 동작한다. 관리자 권한은 fixture 조회·exec·
소유 관계 변경에만 사용했다. 대기 workload와 `/work/ready` 검사로 Ready를 제어했으므로
실제 supervisor의 서버 poll 또는 작업 처리 성공을 주장하지 않는다. 시험 Pod/Secret은 정리했다.

## 로컬 결과 (2026-10-03 KST)

| testRunId | 범위 | 결과 |
|---|---|---|
| 20261002T164203Z-d2ec4bf0 | 최종 단위75개, gateway HTTP5·VD 자격2 포함 | PASS/0 |
| 20261002T164302Z-a3b6e3de | 최종 실제 PostgreSQL102개, VD lifecycle13 포함 | PASS/0 |
| 20261002T163612Z-484a782e | 실제 Kubernetes5개: VD3·기존 Job2,96초 | PASS/0 |
| 20261002T164433Z-861360f1 | 실제 RBAC 허용5·거절4 경계 | PASS/0 |
| 20261002T164536Z-3cfb1d5f | OpenAPI4개·MVC22개·생성 타입/패키징 일치 | PASS/0 |

JUnit failures/errors/skipped는 모두0이다. 초기 gateway 단위73개와 worker 통합101개도 통과했으며
위 최종 실행에는 HMAC2개와 늦은 명령 lease 응답1개를 추가했다. UI/공개 API 계약과 적용된
V1–V14 migration은 변경하지 않았다. 로컬 개발 PostgreSQL은 유지했다.

RBAC 검증은 `create pods --subresource=exec`를 사용한다. 최초 일회성 확인에서
`create pods/exec`를 사용해 yes가 나온 것은 resource/name 문법으로 조회한 오류였다.
CLI help와 정확한 subresource 요청으로 exec 권한이 없음을 확인했다. 제품 권한 변경으로
실패를 덮지 않았으며 실제 Role은 pods get/list/watch/create/delete만 추가 범위에 포함한다.
patch·exec·다른 namespace Pod 생성·Runner Pod 생성은 거절된다.

재현:

```bash
rtk proxy bash scripts/test-unit.sh
rtk proxy bash scripts/test-integration.sh
rtk proxy bash scripts/test-contract.sh
rtk proxy python3 scripts/bootstrap-runtime.py --context kubernetes-admin@kubernetes
rtk proxy bash scripts/test-runtime-kubernetes.sh kubernetes-admin@kubernetes
```

## CI·배포 구분과 다음 게이트

이전 V14 코드b69d805의 CI37031788410은5 jobs/JSON15개 모두 성공했고 실제 Runner 컨테이너27개,
기존kind22Run/S3결과20개를 확인했다. b1e458c pin과 실제 API/UI/MinIO imageID·Ready·PVC Bound·
Argo Synced도 `20261002T163724Z-f4de6ab6`에서 확인했다. 공유 Ingress status 때문에 aggregate
health는 Progressing이다. 상세는 [V14 기록](m6-vd-lifecycle.md)에 있다.

gateway 코드 `cf499be2d4b9e70627ae179a5f9e29c4144cd08e`의
[CI37036686347](https://github.com/dsa04156/edgeai/actions/runs/37036686347)은5 jobs와 artifact 결과
JSON15개 모두 PASS/0이다. 실제 Runner 컨테이너27개·기존kind22Run/S3결과20개·kind 삭제를 확인했다.
pin `86ff9bd`와 실제 API/UI/MinIO imageID·Ready·PVC Bound·Argo Synced는
`20261002T172048Z-00d56a91`에서 PASS/0이다. 공유 Ingress status로 aggregate health는 Progressing이다.
현재 VD 기능은 기본 비활성으로 배포되며 이 CI/배포가 실제 VD 전체 실행을 증명하지 않는다.

다음은 HMAC과 실제 Pod identity를 함께 확인하는 poll 서버, sequence 재전송 영속 처리,
Run VD 배정·Task claim/Result·취소, 공개 provision/교체/Operation/상태 화면 및 demo-vd다.
실제 API 프로세스 재시작·실제 감독 프로세스·실제 작업/결과의 전체 연결 수용을 남겨 둔다.
M5 상태형 복원·외부 실제 계약과 M7–M10도 남으며 전체 플랫폼은 PARTIAL이다.
후속 서버 통신 연결은 [인증된 poll 기록](m6-vd-poll.md)을 따른다.
