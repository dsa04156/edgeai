# M9 저장소 정책 보존 검사의 출력 순서 수정

2026-10-04, source `5a313e104d2fbf552aff0f32e86c9c42153df62d`의
[CI 37203679354](https://github.com/dsa04156/edgeai/actions/runs/37203679354)는
storage의 기존 정책 보존 검사에서 실패했다. 다운로드한 원시 보고서는 앞선7개 통과,
`test-recovery-storage-fence.py:141`의 AssertionError, 소유 프로세스 정리를 확인한다.
복구 CLI의 실패가 아니라 두 `mc admin policy info` 응답의 원문 비교에서 실패했다.

같은 코드로 별도 TLS MinIO를 만들고 실제 정책을40회 조회했다. 원문은2종류였지만
모든 JSON 응답과 정책의 정규화 결과는 같았다. 미니오 정책 집합의 출력 순서는
의미상 보존 여부의 기준이 될 수 없다. 검사를 JSON 내용 비교로 바꾸고, 원래 정책이
다른 복구 ID의 예상 정책과 실제로 같은지도 확인한다. 정책 값의 변경과 잘못된 소유권
수용은 계속 실패하며 운영 복구 동작에는 변경이 없다.

수정 뒤 실제 TLS MinIO25개 전체를 실행한
`20261004T130621Z-a3eb3fd3`가 PASS다. root/기존 서명 URL 거절, 진행PUT2→1→0,
원래2버전과 늦은 완료1버전 보존, 재시작·재개·환경 override·소유 프로세스 정리를
확인했다. 새 커밋의 CI/배포 및 전체 M9 복구 완료를 뜻하지 않는다.

원래 CI는 최종 failure이며 scaffold/runner는 success, images/gitops는 skipped다.
다운로드한3개 artifact의 원시 실행 보고서23개는22 PASS/1 FAIL이었다. 저장소 검사
실패 뒤의 게이트와 새 이미지 배포가 성공한 것으로 해석하지 않는다.
