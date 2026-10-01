# Scripts

- 루트에서 실행. 공유 환경 로딩과 고정 Compose project는 `lib.sh`가 담당한다.
- `bash -n scripts/*.sh` 대신 각 파일에 `bash -n`을 실행해 모든 스크립트를 검증한다.
- exit 0=PASS, exit 2=BLOCKED/미구현, 기타 nonzero=FAIL. 테스트가 없으면 PASS를 반환하지 않는다.
- cluster mutation은 명시적 테스트 context·namespace·소유 label을 확인한 뒤에만 추가한다.
- dev-down은 edgeai-dev만 종료하며 데이터 볼륨을 삭제하지 않는다.
- secret·토큰·인증 헤더를 command line 출력이나 evidence에 남기지 않는다.
