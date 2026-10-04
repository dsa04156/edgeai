# M9 S3 진행 중 요청 소진 검증

2026-10-04, ADR0072. `20261004T083947Z-c4e158e0`의 실제 격리 TLS MinIO 결합25개가 PASS다.
[ADR0071의18개](m9-recovery-storage-fence.md)에 아래7개를 추가했다. 기존 강제 재시작·외부
재개방 사례에서도 새 drain CLI의 성공/거절을 확인했다.

1. 실제 서명 PUT2개가 서버의 `100 Continue`를 받은 뒤 body를 보류했다. root 차단 전에
   인증을 통과해 저장 handler에 들어간 요청이다.
2. root 차단 뒤 실제 metrics 진행 수2를 관측했다.10초 deadline은 BLOCKED이며 두 요청과
   root 거절·원래 파일 버전을 유지했다.
3. 한 PUT의 body64KiB를 뒤늦게 보냈다. root가 이미 차단됐어도200으로 완료되고 정확한
   bytes가 저장됐다. 원래2버전의 ID/SHA는 유지됐다. 나머지 진행 수1에서는 계속 BLOCKED였다.
4. 두 번째 PUT의 연결을 종료했다. 실제 진행/대기 수0과 root probe 완료 누적수 갱신을
   연속2회 확인해 drain이 성공했다. 늦게 완료된 버전을 포함해 총3버전이며 중단된 파일은 없다.
5. 실제 metrics를 바탕으로 완료 counter 누락·음수 gauge·다른 server label을 주입해 거절했다.
   큰 정수의 지수 표기도 값 손실 없이 처리했다. 이 세 거절은 parser fixture다.
6. 실제 admin info에 두 번째 server를 추가한 fixture를 거절했다. 실제 분산 MinIO 시험은 아니다.
7. 실제0 metrics 응답을 반복 재생해 완료 누적수가 증가하지 않으면 BLOCKED임을 확인했다.
   원래 root의 실제 HTTP 요청은 계속 인증 거절됐다.

보고서의 `versionCount=3`, `originalVersionCount=2`, `admittedPuts=2`, `lateCompletedPuts=1`,
`disconnectedPuts=1`, `freshDrainVerified`, `staleMetricsRejected`, `ownedProcessesStopped`가
실제 상태와 일치한다. 모든 시험 TLS socket과 MinIO 프로세스를 정리했다. 원문 URL·새 자격·
관리 응답·TLS 키는 `.tools`에만 남긴다. 공개 요약은 해당 evidence 폴더에 복사했다.

첫 `083549Z-dc9618c5`는 실제 admin 서버 상태가 `online`인데 잘못된 `ok`를 기대해 거절됐다.
실제 응답 계약을 반영한 `083609Z-39652e9a`의24개가 PASS다. 범위 거절을 추가한
`083750Z-cfa8d1df`에서는 deadline 직전 mc subprocess timeout이 FAIL로 분류됐다.
네트워크/조회 timeout을 BLOCKED로 일관되게 처리한 위25개가 최종 결과다. 실패에서도
`inFlightRequestsDrained=false`와 root 차단을 유지하고 소유 프로세스를 정리했다.

선행9caa7dd CI37188905983의 scaffold/storage/runner는 성공했다. 다운로드한 원시24개 모두PASS이며
PG230·이전 S3 root 차단18개·MQTT 차단15개/35계정·Runner111·MQTT95도 확인했다.
images 실제 Kubernetes 게이트는 진행 중이며 그 CI에 이번 drain 변경은 포함되지 않는다.
새25개는 기존 storage CI 게이트에서 동일 빌드 MinIO binary로 실행하도록 연결했다.
새 코드의 원격 CI/배포 확인, 내부 writer/Remote/장치 journal·종합 복구 활성화와 M9 전체는 남는다.
