# 정적 키 파일 백업·복원

이 문서는 대상별 백업·격리 복원 절차입니다. 다른 보호 대상과 복구 순서는 [백업과 복구 안내](../backup-and-recovery.md)를 확인하세요.

DB와 S3 파일 외에 Runner 서명 키, 인증서/CA, 저장소 인증 설정 등 복구에 필요한 **정적 파일**을
명시해서 암호화한다. 실행 환경은 Linux, Python3.11 이상과 고정 age 도구다.
시험에는 OpenSSL도 필요하다. [ADR0065](../../adr/0065-encrypted-private-material.md)의 범위를 따른다.

## 준비

```bash
bash scripts/dev/install-age.sh
```

설치 명령은 공식 age v1.3.2 archive SHA와 실행 파일을 검증한다. amd64 경로는
`.tools/age-v1.3.2/amd64/`, arm64는 `.tools/age-v1.3.2/arm64/`다.
기존 파일이 고정 checksum과 다르면 교체하지 않고 실패한다.

복호화 identity는 백업 대상과 분리된 보호된 위치에서 생성·보관한다. 예를 들어 amd64의 경우
미리 준비한 소유자 전용 디렉터리에서 다음 명령을 사용한다. 키 파일 경로는 새 경로여야 한다.

```bash
.tools/age-v1.3.2/amd64/age-keygen -o /secure/offline/edgeai-recovery.key
.tools/age-v1.3.2/amd64/age-keygen -y /secure/offline/edgeai-recovery.key
```

두 번째 명령의 출력은 공개 수신자다. 아래 `RECOVERY_RECIPIENT`에는 그 공개 값만 넣는다.
native PQ 키는 첫 번째 명령에 `-pq`를 추가할 수 있다. 복호화 개인 키가 없으면 이 도구로
백업을 복원할 수 없으므로 운영 키의 보관·인계 정책은 별도로 정해야 한다.

## 백업

```bash
RECOVERY_RECIPIENT='age1...공개-수신자...'
bash scripts/ops/backup-private-material.sh \
  --file runner/signing.key=/secure/static/runner-signing.key \
  --file stream/tls.crt=/secure/static/stream.crt \
  --file stream/tls.key=/secure/static/stream.key \
  --recipient "$RECOVERY_RECIPIENT" \
  --output /secure/backups/edgeai-material-001
```

원본은 현재 사용자 소유의 정규 파일이며 다른 사용자 권한이 없어야 한다. 예를 들어0600/0400이다.
심볼릭 링크, 하드 링크, 파이프와 변경 중인 입력은 거절한다. Kubernetes Secret 볼륨의 링크를
직접 입력하는 방식은 지원하지 않는다. 운영 Secret을 가져와 정적 파일로 고정하는 절차는 후속이다.

성공한 새 디렉터리에는 `payload.age`와 마지막에 작성한 `manifest.json`만 있다.
manifest의 공개 수신자·개수·총크기는 암호화되지 않는다. 원본 이름/비밀값은 암호문 안에 있다.
성공 manifest가 없는 출력은 완료된 백업으로 사용하지 않는다.

## 복원

```bash
bash scripts/ops/restore-private-material.sh \
  --input /secure/backups/edgeai-material-001 \
  --identity /secure/offline/edgeai-recovery.key \
  --output /secure/restores/edgeai-material-001
```

검증된 파일은 새 디렉터리의 `files/` 아래에 상대 이름으로 생성된다. `restore-report.json`은
`RESTORED_PRIVATE_FILES_ONLY`, `activated=false`를 기록한다. 서비스 설정·Kubernetes Secret·
실행 중인 키는 바뀌지 않는다. 원래 권한 대신 파일0600/디렉터리0700으로 복원한다.

실패 출력에 남은 `failure.json`은 오류 종류만 포함한다. 같은 출력 디렉터리로 덮어쓰지 말고,
실패 원인을 확인한 뒤 새 출력 위치를 사용한다. 임시 평문은 정상 예외 처리 때 제거한다.
작업 호스트와 출력 경로의 접근 권한·디스크 보호는 운영자가 유지해야 한다.

## 검증

```bash
bash scripts/collect-evidence.sh private-material \
  bash scripts/test/test-private-material.sh --report docs/evidence/runs/private-material-report.json
```

시험은 별도0700 경로에 합성 키를 만들고 실제 age/OpenSSL을 사용한다. 진단용 합성 자료는
Git에서 제외한 `.tools/private-material-test-*`에 남고, 공개 보고서에는 비밀값을 넣지 않는다.
CI에는 원시 결과와 안전한 요약만 업로드한다. 실제 운영 키 복구·broker/장치 journal의 일관성·
DB/S3 결합 복원·서비스 활성화 검증은 [M9 잔여 범위](../../requirements/m9-requirements.md)에 남아 있다.
