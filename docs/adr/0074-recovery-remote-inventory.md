# ADR0074: 복원 DB와 차단된 Remote 전체 이력 대조

상태: 로컬 검증. [실제 검증 근거](../evidence/m9-recovery-remote-inventory.md)를 따른다.

ADR0073은 단일 제공자의 새 요청과 계산을 멈춘다. 복원 DB에 없는 할당이나 원본 제공자에서
누락된 할당까지 알아야 이후 복구 결정을 내릴 수 있으므로, 별도 읽기 전용 점검을 추가한다.
점검은 실행 이력을 자동 수정하거나 새 작업을 활성화하지 않는다.

제공자의 `/reference/v1/recovery/allocations`는 별도 운영 자격·providerId/recoveryId를 요구한다.
차단 중이며 실제 worker가 모두 종료했을 때만 전체 terminal 이력을 조회한다. 이 상태에서는
제공자 코드의 새 예약·입력·취소·만료·계산 발행이 이력을 바꾸지 않는다. UUID 오름차순의
keyset 페이지, 한 페이지1..100개, 전체 개수와 nextAfter를 반환한다. 원문 work/parameters/
serviceSpec/입출력 파일/자격은 반환하지 않고 신원·digest·마감·관측·출력 metadata·실행 횟수만 반환한다.

CLI는 각 페이지의 설치/복구 ID·cursor·단조 UUID·중복/개수/상태·전체 목록의 종료를 확인한다.
조회 전후의 제공자 상태 집계도 같아야 한다. 최대1,000페이지와 전체 네트워크 시간 제한을 둔다.
불완전한 페이지를 성공으로 축약하거나 latest/다른 제공자로 대체하지 않는다.
목록의 SHA256은 확인한 metadata의 추적용이며 파일 bytes 검증을 대신하지 않는다.

DB는 기존 복원 보고서의 이름/OID/restoreIdentity와 V33/V34를 검증하고 READ ONLY,
REPEATABLE READ 한 snapshot에서 모든 Remote allocation과 Attempt의 대상 binding을 읽는다.
기존 Kubernetes 점검의 복원 신원 검사를 재사용하되 전용 SQL projection을 전달한다.
대상 digest는 Java RemoteProvider와 같은 origin/protocol/CA bytes/sourceMode canonical JSON으로
계산한다. 실제 Java가 만든 binding과 대조한다. key·digest·SYNTHETIC이 모두 일치하는 대상만
선택한다. CA 파일의 byte 표현과 endpoint 문자열을 임의 정규화하지 않는다. 시스템 trust만 사용한
과거 binding이나 다른 제공자/CA는 이 명령의 명시적 CA binding과 다르며 자동으로 합치지 않는다.

| 분류 | 의미 |
|---|---|
| MATCHED_TERMINAL | 전체 신원·요청 digest·마감이 같고 종료 관측이 기존 revision/terminal 관측과 충돌하지 않음 |
| CANCELLED_BEFORE_RESERVATION | DB는 아직 관측 전이고 제공자에는 동일 신원의 선행 취소 tombstone만 존재 |
| ABSENT_FROM_RESTORED_DATABASE | 제공자 전체 이력에는 있으나 복원 DB에 없는 할당 |
| ABSENT_FROM_PROVIDER | 선택한 DB 대상의 할당을 제공자 전체 이력에서 찾지 못함 |
| BINDING_CONFLICT | 같은 allocation ID의 DB 대상 binding이 선택한 제공자와 다름 |
| IDENTITY_CONFLICT | run/task/attempt/epoch 또는 DB의 work identity가 다름 |
| REQUEST_CONFLICT | 불변 요청 digest 또는 마감이 다름 |
| OBSERVATION_CONFLICT | revision 역행·기존 terminal/동일 revision 내용 불일치·확정 Result와 모순 |

앞의 두 분류만 있고 다른 target이 없으면 종료0/OBSERVED_REMOTE_INVENTORY다. 나머지나
다른 target이 있으면 전체 목록을 유지한 채 종료2/REVIEW_REQUIRED다. 통신/미지원 schema 등으로
목록을 완성하지 못하면 성공 inventory를 쓰지 않는다. 이를 전체 복구 성공으로 해석하지 않는다.
DB에 남은 비종료 상태의 조정, 외부 실행기, 다른 provider 설치, 장치 journal, 결과 bytes와
종합 활성화는 별도다. databaseModified/globalQuiescenceProven/activated는 false다.

제공자 DB 복제본의 동시 실행이나 운영자 직접 SQL 변경은 이 단일 설치의 불변성 계약 밖이다.
복원 DB도 조회한 snapshot 이후의 직접 변경까지 고정하지는 않는다. 원문 오류와 자격을 출력하지
않고 새700 디렉터리/600 inventory에만 metadata를 보존한다.
