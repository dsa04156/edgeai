# M9 Remote/Kubernetes 복구의 CI와 실제 배포 검증

## 최신: ADR0085–0088 포함

2026-10-04. 소스 `42c4094f3a3c8cf4ac0cceedac81174332650ca1`,
[CI37209512541](https://github.com/dsa04156/edgeai/actions/runs/37209512541)의5개 job과
다운로드한 원시34개가 모두 PASS/exit0이다. 감사 `20261004T153553Z-573e4203`.
PostgreSQL230/단위122/Runner111/MQTT95, 실제 STREAM24개/결과54개, 복구 retirement69개,
Remote결과15/실패15개, 장치 암호화 백업13개와 DB/journal 대조16개를 확인했다.

GitOps `6d876f554160df7c553febf6757ba16c9299ac4c`의 정확한 API/dashboard/MinIO imageID,
Ready/PVC Bound/Argo Synced는 `20261004T153534Z-5f51c056` PASS다. 공유 Ingress의
aggregate health는 Progressing이다. 이후 `20261004T153651Z-90a91c7f`에서 기존 파일10개의
고정 version/bytes/SHA·원래 두 PVC UID, HTTPS256KiB PUT/stat/GET·익명403과 probe 소유
object/bucket 정리를 확인했다. 원래 storage baseline은 다시 캡처하지 않았다.

대기 중이던 ADR0089–0091을 이 pin 변경 위로 rebase한 뒤 검증한 소스 `9310ad0`과의
차이가 배포 pin2개뿐임을 확인했다. 그 변경의 장치/원본 broker/owner·STREAM경로47개와
MQTT97개는 새 원격 CI에서 별도로 확인한다. 위34개는 이 후속 기능의 CI 성공 근거가 아니다.

## 선행: ADR0081–0084 포함

2026-10-04. 소스 `fd8830db36bfc329d4047af41e594a12054d8b9e`,
[CI37204890109](https://github.com/dsa04156/edgeai/actions/runs/37204890109)의 5개 job과
다운로드한 원시 보고서 32개가 PASS/exit0이다. 감사 `20261004T142230Z-e8e25801`.
PostgreSQL230/단위122/Runner111/MQTT95와 실제 STREAM24개·결과54개를 포함한다.
복구 retirement 56개에서 VD Task/workflow/BATCH offload·claim되지 않은 Job도 검증했다.

GitOps `4a13ec13d50154aa33e60e70cd04888d9ffb347e`의 정확한 API/dashboard/MinIO 이미지,
Ready/PVC Bound/Argo Synced는 `20261004T142255Z-670ebdc4`에서 확인했다. 기존 공유
Ingress의 aggregate health는 Progressing이다. 뒤이어 `20261004T142314Z-d47d0242`에서
기준 파일 10개의 version/bytes/SHA, 기존 PVC UID, HTTPS 256KiB PUT/stat/GET·익명403과
시험 소유 object/bucket 정리를 확인했다. 원래 baseline 두 개는 다시 캡처하지 않았다.

이 CI에는 ADR0085–0088의 혼합 Remote/실패 처리/장치 journal 백업·DB 대조가 포함되지
않는다. 해당 변경의 로컬 근거와 새 CI를 별도로 확인한다. 전체 플랫폼 완료 판정은 아니다.

## 선행: 기본 Kubernetes retirement

2026-10-04. 검증 소스는 `be41a8bc872f7b7ed4b065d524a3645d6e0a2ca2`,
[CI37200100790](https://github.com/dsa04156/edgeai/actions/runs/37200100790)이다.
scaffold/storage/runner/images/gitops 5개 job이 모두 success이며 다운로드한 원시 보고서32개는
모두 PASS/exit0이다. 전체 감사 `20261004T125138Z-05565ce2`로 확인했다.

PostgreSQL230·단위122·Runner111·MQTT95, 실제 kind BATCH22Run/결과20개, STREAM24개/
43Pods/결과54개, VD 스트림33Pods를 포함한다. 신규 Remote 결과14개·실패/재시도15개와
Kubernetes retirement 기본16개도 통과했다. 기본 retirement는 정확한 빌드 API 이미지에서
추출한 JAR·해당 소스의 Runner digest·Compose PostgreSQL17을 함께 검증한다.

GitOps `2f882052413e958d58e9f02ecba133a60ed1825e`의 실제 API/dashboard/MinIO imageID,
Ready 상태, PVC Bound, Argo Synced와 VD 실행 설정을 `20261004T125137Z-ff9020e5`에서
확인했다. Argo aggregate health는 기존 공유 Ingress 때문에 Progressing이며 Healthy로
판정하지 않았다.

배포 확인 뒤 `20261004T125229Z-73489f8b`에서 원래 기준 파일10개의 고정 version/bytes/SHA,
PostgreSQL/MinIO PVC UID, HTTPS256KiB PUT/stat/GET·메타데이터·익명403을 검증했다.
시험에서 만든 object/bucket만 정리했고 두 원래 baseline 파일을 재캡처하거나 덮어쓰지 않았다.

이 소스에는 ADR0081 VD Task 확장·ADR0082 workflow 조정·ADR0083 BATCH offload 확장이
포함되지 않는다. 후속 변경은 로컬46개/Remote15개 근거와 구분하며 새 CI/배포 확인이 필요하다.
후속 세 커밋을 pin 변경 위로 rebase한 뒤 테스트한 소스와의 차이가 배포 pin2개뿐임을 확인했다.
위 성공은 M5 잔여/M7–M10 전체 또는 종합 복구 활성화 완료를 뜻하지 않는다.
