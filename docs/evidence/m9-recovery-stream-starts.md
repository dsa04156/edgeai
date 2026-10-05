# M9 — 복원 STREAM 그룹의 원래 시작 허가

[ADR0108](../adr/0108-restored-stream-starts.md), [복구 명령](../recovery-stream-workflows.md).

## 검증 경계

공개 API의 Device fanout 두 작업과 BATCH 후속 작업을 사용한다. 실제 Kubernetes의 원본
부모/자식 프로세스를 종료한 뒤 successor를 실행한다. `--vd-peer`는 공개 `taskExecutions`로
동료 작업을 VD에 배치하며, 같은 VD의 다음 supervisor 세대에서 successor를 배정한다.
readiness·allocation·DB binding·전환 이력은 명시적 fixture다. 실제 TLS API와 Pod-bound
TokenReview를 통해 각 target의 시작 기록을 생성하며 잘못된 proof 거절과 재요청의 최초
S3 version 보존을 확인한다. 실제 모델 계산이나 결과 성공을 대신하는 시험은 아니다.

시작 전 DB snapshot과 이후의 독립 S3 백업을 만든 뒤 원본 DB/API/저장소를 제거한다.
실제 부모/자식 종료·원래 MQTT 권한 차단 후 복원 DB에서 다음을 검사한다.

- 모든 member의 원래 시작 허가와 고정 작업/신원/기한. VD는 배정/세대/세션/슬롯·설정/lease.
- 옵션 생략·한 기록 누락·삭제 marker·같은 bytes의 최신 S3 version 교체 시 부분 성공 거절.
- 기록된 취소·더 최신 Attempt·DB 관측 이후 경합·SQL 오류에 의한 전체 원복.
- 실제 CLI에서 전환 Operation만 성공으로 복원하고 다른 테이블과 원래 이력 보존.
- COMMIT 응답 유실·commit 후 S3 변경·같은 복구의 변경 없는 재실행, 격리와 소유 자원 정리.

## 실행 기록

최종 로컬 결합55개 `20261005T010404Z-5743138b` PASS. NODE/VD 실제 시작 기록2개,
복원DB21개(기존18+시작 복원3), 실제 부모/자식4쌍 종료, 고정 checkpoint2/version2를 확인했다.
시작 복원에서는 원래 전환1개만 성공으로 반영하고 타43테이블을 보존했다. 새 claim/Result는0,
VD peer의 배정/세션 등을 바꾼12가지 관측은 거절했다. 실제 post-commit S3 교체1건의
최신 version 불일치로 성공 보고를 거절하고 intent/격리를 유지했으며 원래 version으로
되돌린 뒤 재실행은 변경0이다. 모든 소유 DB/namespace/API/MQTT/저장소 정리를 확인했다.

공유 fixture 변경을 포함한 최종 기존 경로 회귀도 모두 PASS다.

| 범위 | 실행 근거 | 결과 |
|---|---|---|
| NODE/VD STREAM 그룹 복구 | `20261005T010404Z-5743138b` | 55개·소유 정리 |
| Kubernetes 결과 복원 | `20261005T010839Z-139f2712` | 20개·소유 정리 |
| VD 결과 복원 | `20261005T011238Z-0d2351e5` | 24개·소유 정리 |
| Kubernetes/VD/Remote 시작·종료 복구 | `20261005T010218Z-e0126123` | 91개·소유 정리 |

위190개는 변경 관련 로컬 범위다. 결과 복원20/24개는 실제 post-commit S3 version 교체가
발생했는지도 새로 검사한다. JAR SHA-256 `ac681d4ca1bcf588f0fbd3b8a4fb17876097567c0ff1557def6f88029cd9a453`,
V36 SHA-256 `ed078e8517e85c403dac9df8a8cd5ea777cb3497cf0bf408495e6c44bdb6fb2e`는 불변이다.
실행 컨테이너는 기존 검증 소스 `077d1398d5cbadf2bba9b0d8af9c8df2067b0c5a`의 digest pin이며
새 Python 복구 코드는 호스트에서 실행했다. 새 원격 CI·배포 및 전체 목표 수용은 별도다.
최종 감사 `20261005T011630Z-338f9a9a`에서 원시190개/정리·JAR/V36·대상 소스 해시·
kind gate 연결을 대조했다. 원래 시작 기한이 지난 후에도 최초 허가를 검증해 복원한
실제 intent와, commit 후 S3 교체1건 때문에 성공 보고를 보류한 근거를 확인했다.

## 실패 재현과 수정 경계

`005455Z-d959826b` 혼합 구성에서도53개 통과 후 동일한 시험 클라이언트 수명 오류를
재현했다. 비공개 진단에 `Storage publication deadline expired`, `replacements=0`,
intent 보존/성공 보고 없음이 기록됐다. 첫 실행의 최초 저장소 조작과 마지막 intent 간격도
44.296초로30초 fixture 예산을 넘었다. 각 독립 시험 조작에 새 bounded client를 사용하도록
수정했으며 생산 코드의 관측 제한 시간은 바꾸지 않았다. 위 최종55개에서 실제 교체1건과
그에 대한 거절을 확인했다.

공유 인증·VD supervisor fixture 변경의 기존 VD 결과 복원24개 `005951Z-a5b45a08`과
Kubernetes/VD/Remote 시작 복구91개 `010218Z-e0126123`는 소유 자원 정리까지 PASS다.
아래 CI에서 확인한 추가 fixture 수정 뒤 결과 복원20/24개도 위 실행에서 재검증했다.

- `003952Z-2f7ce336`: NODE/AUTO 구성에서 기존 경로와 새 시작 복원 포함53개 통과 후 마지막
  post-commit 시험의 검증문에서 FAIL. 원본 제거/실제 CLI 성공 복원/DB 경합·원복/응답 유실을
  수행했으며 소유 API·DB·namespace·저장소/MQTT 정리를 확인했다. 제한 시간이 있는 시험용
  S3 클라이언트를 여러 독립 검사에 재사용한 경로를 확인하고 진단을 추가했다.
- `004912Z-62b7c4a8`: NODE/VD 혼합 구성34개 통과 후 기존 누락 증거 fixture에서 FAIL.
  VD의 원래 NULL Job UID를 지우는 동작은 증거 누락이 아니었다. 혼합 그룹의 실제 Job
  member를 대상으로 바꿨다. DB 제약이나 복구 검증은 완화하지 않았다. 소유 자원 정리 확인.
- `003656Z-1c5ed65f`: 초기 Profile 등록 HTTP 요청 timeout. API/DB 대기 근거가 없어 원인은
  미확정이며 재발 시 정리 전 스레드·DB 대기를 비공개로 저장하도록 보완했다.
- `004738Z-b1ea4cce`: VD Pod 생성 요청 실패. 원 응답이 보존되지 않아 원인은 미확정이다.
  실패 응답을 비공개 파일로 남기도록 보완했다. 후속 혼합 실행에서 Pod 생성은 통과했으나
  원인이 해결됐다는 의미는 아니다. 두 실패 모두 소유 자원 정리를 확인했다.
- `010620Z-751a18b4`: 기존 결과 복원 시험의 TLS Remote fixture가 기동 준비 확인에서
  실패했다. 업무 case0이며 API/DB/namespace 생성 전이다. 상세 기동 출력이 없어 원인은
  미확정이다. 후속20/24개 PASS와 구분하며 timeout 완화나 생산 코드 변경은 하지 않았다.

초기 Paho/로컬 broker 환경 누락과 임시 실행 wrapper 인자 오류는 각각 `003605Z-1a5ffa92`,
`003625Z-43f51635`, `003645Z-c3d83e6b`에 보존한다. 기존 로컬 고정 실행 환경을 재사용했다.

## 재현

```bash
EDGEAI_STREAM_PYTHON=<고정-Paho-환경>/bin/python \
  bash scripts/collect-evidence.sh recovery-stream-vd-starts \
  bash scripts/test-recovery-stream-workflows.sh \
  --context '<시험 context>' --offloads --finalizers --runtime-start-journals --vd-peer \
  --minio-binary '<검증한 MinIO 실행파일>' \
  --report .tools/recovery-stream-vd-starts.json
```

local PostgreSQL·MinIO·Mosquitto 실행 환경은 기존 시험과 동일하게 지정한다. Runner는 배포
pin 또는 명시한 digest/source를 사용한다. kind gate에는 같은 혼합 검사를 연결했다.
새 원격 CI/배포, STREAM Result/finalization 권한, unknown 실행 발견/회수·전역 writer/API
차단·종합 활성화와 M5 잔여/M7–M10 전체 수용은 남는다.

## 선행 CI의 별도 실패

소스 `edc9625`의 [CI37245085073](https://github.com/dsa04156/edgeai/actions/runs/37245085073)은
5jobs 성공/images 실패/gitops 생략으로 종료했다. 산출물 감사 `010603Z-583dff39`에서
원시39개 중37PASS/2FAIL, 기존 시작 복구91개 PASS, Kubernetes 결과 복구19/20 뒤 Blocked,
소유 DB/namespace/API/저장소 및 kind 정리를 확인했다. STREAM 복구 단계에는 도달하지 않았다.
이번 ADR0107/0108의 새 코드 검증 또는 새 배포 성공으로 취급하지 않는다.

결과 시험의 한120초 저장소 클라이언트가 전체 검사 동안 재사용되는 경로와, 최초 시작
기록 이후 마지막 삭제까지120초가 넘는 CI 로그를 확인했다. 원 CI의 상세 예외 본문은
보존되지 않아 그 Blocked의 직접 원인 단정은 피한다. 같은 수명 결함을 수정하고,
post-commit 시험이 실제 S3 version을 정확히 하나 만들었는지 반드시 검사하도록 보강했다.
생산 저장소 timeout을 늘리거나 검증을 생략하지 않았다. 새 전체 CI로 원격 수용을 확인한다.
