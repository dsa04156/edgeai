# 원래 Remote 시작 기록과 복원 전환·S3 결과의 결합 검증

[ADR0100](../adr/0100-recovery-remote-start-receipts.md)은 실제 원격 시작 접수 기록을 복원된
BATCH 전환의 원래 신원/기한과 대조한다. 새 옵션은 `--remote-start-receipts`이며 기존
`--offloads --remote-connection`과 Kubernetes/VD·Remote 실행 정리가 필요하다.

최종 결합79개 `20261004T195042Z-fa32af0a` PASS. 복원 DB6개, 실제 컨테이너 부모/자식5쌍,
Remote 할당6개, 실제 TLS 제공자/SQLite와 별도 TLS MinIO를 사용했다. 원래69개 경로에
새10개를 더했다. 초기78개 `20261004T194628Z-87eea1f3`도 PASS였으며 이후 최신 Attempt의
실제 DB 행을 추가한 거절 검증과 최종 미해결 전환 수를 반영해 전체를 재검증했다.

- 실제 시작 전에 DB 전환/기한을 고정하고 제공자가 성공 계산 전에 기록한 영수증1개를 조회한다.
  제공자 재시작 뒤에도 기록이 유지되고, 복구 시점에는 원래 시작 기한이 이미 지났다.
- 나머지5개 legacy 할당에는 실제 시작 영수증이 없다. 옵션 없는 기존 경로는 성공을 계속
  미해결로 두고, 명시적 부재 응답 fixture도 기록을 만들거나 전환을 확정하지 않는다.
- 실제 TLS 응답에서13종의 관측 fixture를 변형해 미지원 endpoint·제공자/복구/Attempt·digest·
  lease·전환/기한·늦거나 너무 이른 접수·UTC/스키마 불일치를 거절한다. 제공자 영속 기록은
  바꾸지 않으며 이 부분은 잘못된 응답의 주입 시험이다.
- prepare 뒤 접수 시각 변경은 SQL 제출 전에 거절한다. 실제 DB의 기한 변경·기록된 취소,
  새로운 epoch의 Attempt 행도 과거 성공으로 덮어쓰지 않는다.
- 최종 관측 이후 실제 전환 행 변경은 transaction guard로 차단한다. 실제 PostgreSQL trigger
  오류는43개 테이블을 보존한다. COMMIT 성공 뒤 응답 유실은 intent를 남긴다.
- 재실행은 전환 추가 변경0이다. 최초 성공 반영은 task_offload 한 테이블만 바꾸며 다른42개,
  source OFFLOADED/종료 이력·target Attempt·원래 배치/기한·제공자 계산 횟수를 보존한다.
- 전환 성공 조정 다음 기존 결과 복구로 실제 고정 S3 version/bytes/SHA/출력 계약을 검증한다.
  Result1개·Task/Attempt 성공을 반영하고 같은 입력 재실행은 변경0이다. 새 실행/재시도와
  서비스 활성화는 없다. 기존 격리·Pod 증거를 유지한다.

실행 명령은 다음과 같다. 시험 전용 namespace·DB·API·제공자·MinIO 정리가 모두 확인됐다.
업무 신원/할당/전환은 명시적 SQL fixture이며 실제 외부 제공자 수용을 뜻하지 않는다.

```bash
bash scripts/test/test-recovery-kubernetes-retire.sh \
  --context <명시적-시험-context> \
  --vd-tasks --workflows --offloads --unclaimed-jobs --remote-offloads \
  --remote-start-receipts --minio-binary <시험-MinIO-실행파일>
```

기존 원격 실패15개 `20261004T194916Z-94803bf0`, 결과15개
`20261004T195043Z-d0759979`도 PASS이며 전용 DB/프로세스 정리를 확인했다.
전체 검증의 API JAR SHA256은 `b0f4aff1af44f19268d9b1c488432df43b489b881e7c51d7fc5fa068e9319c67`다.
실제 컨테이너는 소스60c8be3의 Runner index
`sha256:8a2f8067c0bd3d5a10e76b2c46e145b045d8a0789c3f01c10984e1af915ac2ff`다.
이번 변경은 복구 Python 경로이며 API/Runner 구현이나 DB migration을 바꾸지 않았다.

CI kind 명령에도 새 옵션을 연결했다. 이 변경의 원격 CI·배포는 아직 확인하지 않았다.
현재 진행 중인 선행 f66c4cd CI에는 ADR0100이 없다. 실제 외부 계약·Kubernetes/STREAM 시작
권한·전역 writer 종료·새 권한 적용·종합 복구 활성화와 M5 잔여/M7–M10 전체 수용은 남는다.
