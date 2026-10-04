# 장치 관리 부하 시험

M8의 HTTP 관리 경로를 실제 Spring Boot와 PostgreSQL로 측정한다. 합성 Device 등록·세션·관측·
상세/페이지 조회·재접속을 대상으로 하며 MQTT payload·AI 모델·물리 센서·Dashboard 성능 시험은 아니다.
[측정 계약](adr/0057-device-management-load.md)을 따른다.

## 실행

프로젝트 `.env`, JDK21, Python3, PostgreSQL과 `psql`이 필요하다. `scripts/lib.sh`의 JDK와
프로젝트 로컬 PostgreSQL client를 재사용하며 의존성을 설치하거나 DB 권한을 확대하지 않는다.
DB 사용자는 시험 전용 데이터베이스를 생성할 수 있어야 한다. 기존 프로젝트 DB에 시험 행을
추가하지 않는다. 별도 API를 loopback의 빈 포트에서 시작하므로 dev API를 종료할 필요가 없다.

```bash
# 10대·2초 예비 시험: 전체 규모 또는 성능 수용 판정이 아님
bash scripts/collect-evidence.sh m8-smoke bash scripts/test-load.sh \
  --counts 10 --seconds 2 --interval 1 --measure-only --report .tools/device-load-smoke.json

# 기본100→300→1,000대, 각60초·장치당10초 주기 측정 및 정합성 확인
bash scripts/collect-evidence.sh m8-baseline bash scripts/test-load.sh \
  --measure-only --report .tools/device-load-baseline.json

# 성능 예산이 아직 없으면 측정 뒤 exit2/PENDING_ACCEPTANCE
bash scripts/test-load.sh

# 예산이 합의되면 --measure-only 대신 해당 수치를 --max-p95-ms로 제공
# 보고서에 입력 예산을 고정하고 모든 단계의 예정 시각 기준 p95와 비교
```

`--counts`는 증가하는1~1,000 값, `--seconds`는2~600, `--interval`은1~30초,
`--workers`는1~64(기본32)다. seconds는 interval의 배수여야 한다. API 자원 표본을
확보하기 위해 각 측정 구간은 최소2초다. 소수 장치 실행은 `fullScaleSequence=false`다.
기본 전체 순서를 실행했더라도 측정 성공만으로 합의된 성능 수용을 충족했다고 표시하지 않는다.

## 보고서 해석

- `summary.offeredRps`: 예정한 관측과 상세 조회를 합한 부하. 기본11/33/110RPS이며,
  장치 수100/300/1,000과 구분한다.
- `requestMs`: 실제 요청 시작부터 응답까지. `scheduledLatencyMs`는 예정 시각부터 응답까지이므로
  대기열·발송 지연을 포함한다. `maxDispatchDelayMs`와 모든 개별 표본을 함께 보존한다.
- `unexpected`, `dropped`: 기대하지 않은 상태·응답·연결 오류 및 꽉 찬 대기열의 미발송 수.
  하나라도 있으면 실패다. 오류를 숨기는 자동 재시도나 부하 하향은 없다.
- `integrity`: 페이지 조회의 정확한 장치 집합, DB의 최신 세션/sequence·총 관측 수·본문 속성,
  동일 요청 재전송·새 세션 epoch·이전 세션 거절 결과다. 등록과 정합성 probe는 timed window 밖이다.
- `apiResources`: `/proc`로 측정한 해당 API의 최고 RSS와 CPU 사용량이다. CPU core equivalent1은
  표본 구간에서 CPU1개분 사용을 뜻하며, 백분율이나 CPU quota가 아니다. JVM 최대 heap은512MiB,
  Hikari pool은5개다. 도구는 별도 CPU 제한을 설정하지 않는다. 상위 실행 환경의 CPU 제한은
  수집하지 않으며 `cpuQuota=null`은 무제한 확인을 뜻하지 않는다. 호스트와 PostgreSQL instance는 공유한다.
- `database`: 해당 DB의 크기·접속 수·누적 transaction/deadlock 통계다. 공유 PostgreSQL 전체의
  CPU를 이 시험 전용 CPU로 표시하지 않는다. DB 연결/transaction 통계는 sampling 시점 값이다.
- `ownedApiStopped`, `ownedDatabaseRemoved`: 시험이 만든 프로세스와 DB의 정리 확인이다.
  임시 DB 이름/oid를 대조하고 API 종료 후 그 DB만 제거한다. 실패에도 부분 report를 저장한다.

`--measure-only`의 exit0은 `MEASURED` 범위의 정합성·측정 성공이다. 인자 없이 실행하면
성능 예산 미정으로 exit2가 된다. 명시적 예산을 제공한 전체 규모 실행은 모든 예정 시각 기준
p95가 예산 이내일 때 exit0이며, 임의 CLI 수치를 실제 장비의 합의 기준으로 바꾸어 해석하지 않는다.
원시 report를 `collect-evidence` 실행 폴더에 복사해 보존한다. API 진단 로그는 `.tools/edgeai_load_*/api.log`
안에0600 권한으로 남으며 Git/evidence에 복사하지 않는다. 비밀번호·CSRF·cookie·응답 본문은 metrics에 없다.

## 판정 회귀와 현재 근거

`bash scripts/test-load-acceptance.sh`는 실제 API/전용 DB에서 각각10대·2초로 측정 전용,
예산 미정, 초과 예산, 소규모 예산 통과의 네 가지 종료 코드·보고서·DB 정합성·정리를 검사한다.
GitHub Actions scaffold에도 같은 게이트를 연결한다. 이 회귀 자체의 성공은 전체 규모나
합의 성능 수용을 뜻하지 않는다. 원시 보고서는 `docs/evidence/runs/*-load-acceptance-*/`에
보존하며 CI의 platform-verification artifact에도 포함한다.

최초 전체 규모 측정과 남은 범위는 [M8 측정 근거](evidence/m8-management-load.md)를 따른다.
