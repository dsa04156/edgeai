# ADR 0090: 원본 Device source 소유권 종료와 최종 snapshot 연결

상태: 채택. 2026-10-04.

ADR0089의 데이터·원본 브로커 검사는 원본 Device journal의 실행 소유권 해제를 증명하지
않는다. 실행 중 만든 백업은 마지막 프레임을 포함하지 않을 수 있으며, 과거 종료 보고서만
사용하면 잠금 파일 교체나 이후 쓰기를 놓칠 수 있다.

명시적 로컬 종료 명령은 동일 Device Session·경로·generation을 확인하고 비공개 journal에
복구 UUID·부팅 신원·DB/owner.lock inode를 고정한 retirement.json을 영속 기록한다. SDK는
소유권 획득 전후와 transaction 전후에 이를 확인한다. DeviceSource의 실행 단계에서 감지하면
미완료 transaction을 되돌리고 MQTT와 journal을 닫는다. 다른 프로세스에 임의로 signal을
보내지 않고 제한 시간 안에 원래 잠금의 실제 exclusive 획득이 가능해야 종료로 인정한다.

잠금을 놓지 않은 owner는 BLOCKED이며 marker는 보존한다. 같은 복구 UUID로 재시도한다.
marker가 존재하면 원래 journal의 SDK 재시작은 거절된다. 파일 교체·다른 복구 intent는
자동 수정하지 않는다. 삭제된 원본·다른 호스트/부팅으로 기존 보고서를 옮겨 재사용하지 않는다.

종료 직후 논리 snapshot SHA를 기록한다. 전달된 복원본과 다르면 종료 자체는 완료하되
snapshotMatchesSuppliedRestore=false로 보고한다. 원본의 최종 데이터를 다시 암호화/복원해야
후속 결합 검사를 통과할 수 있다. 기존 journal·프레임·완료 intent는 삭제하지 않는다.

ADR0089 명령에 원본 경로와 종료 보고서를 함께 제공하면 실제 lock·inode·marker·snapshot을
검사 전후에 확인한다. DB/S3/브로커의 동일 복구 UUID와 최종 복원 snapshot에 연결하며
중간 원본 쓰기/원본 유실/잠금 재획득 시 결과를 거절한다. 한 인자만 주면 실패한다.

증명 범위는 sourceJournalOwnerQuiescenceProven이다. 특정 host 전체나 모든 producer
프로세스의 종료를 의미하지 않으므로 producerProcessQuiescenceProven·globalQuiescenceProven·
activated는 false다. 원본 API/다른 producer 권한 회수, 새 Secret·broker grant·종합 활성화는
후속 절차다. Linux 로컬 잠금 계약이며 NFS/분산 잠금이나 실제 장비 수용으로 확장하지 않는다.

실제 별도 writer와 HTTPS/MQTT DeviceSource 프로세스, transaction 중단·잠금 경쟁·최종
암호화 백업, 공개 API/복원 PG·TLS MinIO/Mosquitto 결합으로 검증한다. 서버 JAR·V1–V34와
DB schema는 변경하지 않는다. 시험의 런타임 claim/checkpoint receipt는 기존 SQL fixture다.
