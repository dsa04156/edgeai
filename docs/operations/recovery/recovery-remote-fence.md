# 참조 Remote 제공자 복구 차단

> **심화 운영 절차** — 격리된 복원 환경에서 사용하는 구성 요소별 절차입니다.
> 적용할 schema·대상 신원·선행 보고서는 아래 조건을 따릅니다. 이 명령의 성공만으로 서비스를 재가동하지 않습니다.
> 전체 순서는 [백업과 복구 안내](../backup-and-recovery.md)를 확인하세요.

## 목적과 선행 조건

[ADR0073](../../adr/0073-recovery-reference-remote-fence.md)은 SYNTHETIC 참조 제공자의 전체
계산을 중단하고 이전 controller의 접근을 영속 차단한다. 실제 외부 업체 API에는 사용하지 않는다.
전용 제공자 전체가 대상이며 다른 업무와 공유한 제공자에 부분 적용하는 명령은 아니다.

제공자 실행 시 일반 token-file과 다른 값을 가진600 파일을 `--recovery-token-file`로
설정한다. `simulator/remote_server.py`와 `deploy/kind/remote-provider.py`가 지원한다.
기본은 비활성이다. TLS launcher에는 기존 cert-file/key-file도 필요하다. 운영 자격을
플랫폼 API의 일반 제공자 토큰으로 설정하지 않는다. 실제 배포의 Secret 적용은 자동 실행하지 않는다.

먼저 CA와 별도로 확인한 인증서 지문으로 설치 ID를 조회한다. 아래는 자리표시자다.

## 실행

```bash
bash scripts/ops/fence-recovery-remote.sh --inspect \
  --endpoint https://remote.example:8443 \
  --ca-file /private/remote-ca.pem \
  --certificate-sha256 <확인한-인증서-SHA256> \
  --recovery-token-file /private/recovery.token \
  --output /private/remote-inspection-new
```

## 결과 확인과 제한

`fence-report.json`의 observed.providerId가 의도한 설치인지 확인하고 동일 복구 UUID를 보관한다.
차단 명령은 새 예약·입력·start·cancel·조회 접근을 막고 전체 DB 할당에 취소를 요청한다.
이미 완료한 결과/입력/이력은 유지한다. 백업 DB에 없는 할당도 제공자의 전체 집계에 포함한다.

```bash
bash scripts/ops/fence-recovery-remote.sh \
  --endpoint https://remote.example:8443 \
  --ca-file /private/remote-ca.pem \
  --certificate-sha256 <확인한-인증서-SHA256> \
  --provider-id <조회한-설치-UUID> \
  --recovery-id <이번-복구의-UUID> \
  --recovery-token-file /private/recovery.token \
  --controller-token-file /private/previous-controller.token \
  --timeout 60 --output /private/remote-fence-new
```

timeout은10..300초다. 종료0/SOURCE_REMOTE_FENCED는 전체 비종료 할당0·실제 계산 스레드0·
기존 controller403 확인을 뜻한다. 종료2/BLOCKED 또는 응답 유실은 완료가 아니다. 서버에 차단이
이미 적용됐을 수 있으므로 자동 rollback하지 않는다. 같은 대상/복구 ID와 새 출력 경로로 재개한다.
`fenceRetained`는 마지막으로 확인한 상태이며 장애 뒤의 현재 상태는 다시 조회해야 한다.

프로세스 재시작에도 차단과 ID가 유지된다. 새 recovery ID로 덮어쓰기·자동 해제·기존 API
재활성화는 지원하지 않는다. 복원 DB 조정·새 권한 적용·외부 Remote/장치 journal·종합 복구는
별도 단계다. CA/인증서 교체를 기존 지문으로 통과시키지 않는다.

검증: `bash scripts/test/test-remote.sh`는 실제 TLS 복구 시험과 기존 Java gateway 회귀를 함께 실행한다.
[OpenAPI](../../../contracts/openapi/remote-reference-api.yaml)에 각 복구 API의 역할·자격·상태를 설명한다.
종료 확인 뒤 [복원 DB/Remote 이력 점검](recovery-remote-inventory.md)으로 전체 할당의 누락·충돌을 대조한다.
