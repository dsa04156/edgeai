# M9 Remote/Kubernetes 복구의 CI와 실제 배포 검증

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
