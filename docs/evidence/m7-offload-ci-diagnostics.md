# M7 VD 그룹 전환 CI 실패와 후속 진단

2026-10-04. 소스 `5f804559dc4485738481f53dc49ea8549e037594`의
[CI37181374516](https://github.com/dsa04156/edgeai/actions/runs/37181374516)은
runner/storage/scaffold 성공, images 실패, gitops skipped로 종료했다.
완료된3jobs의 원시21개와 PG230·Runner111·MQTT95 성공은 전체 CI 성공을 의미하지 않는다.

이미지 artifact의 `20261004T061426Z-f9588dcf`는 kind FAIL이다. 실패 위치는
`vd-distinct-offload-offload-releasing`, `AssertionError`, 당시 `vd_stream_acceptance.py:202`다.
이 조건은 기존/새 Attempt의 수·상태와 epoch 전환을 확인한다. 실패 당시 값은 기록되지 않았고
자동 취소 뒤 수집한 상태로 원래 실패 값을 복원할 수 없어 원인은 미확정이다.
테스트가 만든 kind 클러스터의 제거 로그는 확인했다. API/Dashboard 새 이미지 발행과
GitOps 배포는 수행되지 않았다.

새 진단은 실패 시 **취소 전에** Task 종류·기대 상태·최대4개 Attempt의 ID/번호/상태/epoch를
기억하고, 최종 보고서에 허용한 필드만 복사한다. UUID·열거 상태·정수 범위를 검증하고
응답 본문/예외 메시지/토큰은 수집하지 않는다. 기존 성공 조건과 timeout은 유지했다.
진단 회귀3개 `20261004T070243Z-9edc1395`는 허용 필드 보존·비정상 타입 거절·비밀 canary
배제를 통과했다. 같은 검사를 CI scaffold에 추가했다.

후속 기존 클러스터의 단일 `vd-distinct-offload` 재검증
`20261004T070243Z-4715e4c0`은 PASS다. 실제 Node Pod1개·VD Pod3개, API 교체 중 대기/재전송,
전환 후 상태 인계, 고정 S3 결과3개의 버전/길이/SHA와 소유 자원·Job 장벽 정리를 확인했다.

| 입력 | 후속 실행 |
|---|---|
| API | local JAR, SHA256 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7` |
| Runner | 5f80455, digest `sha256:6c35d1a9b047bfcbd34d99c7975f89a4e11df151a7e79b361de67d5b78a5c9d0` |
| MinIO | digest `sha256:bb3d357761a937d93154111a23b0d770a174458ed8ba2302b5e21af9cc308876` |

실패한 CI의 미발행 API 이미지를 재사용한 시험은 아니다. 기존 클러스터의 단일 성공으로 CI의
실패 원인이 해결됐다고 판정하지 않는다. 다음 CI의 실패 진단 또는 전체 성공 산출물을 확인하고
별도로 실제 배포/기존 데이터 보존을 검증해야 한다. M7 전체 수용은 유지한다.
