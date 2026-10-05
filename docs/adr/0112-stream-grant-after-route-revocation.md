# ADR0112 — 데이터 연결 종료 뒤 완료 응답 지연 처리

## 문제와 결정

그룹 완료 허가가 DB에 기록되면 데이터 route를 종료할 수 있다. 그러나 독립 S3 기록
확인이나 응답 유실 때문에 Runner가 FINALIZE 응답을 받는 시점은 늦어질 수 있다.
기존 Runner는 다음 Session step의 연결 권한 거절 뒤 완료 허가를 한 번만 다시 읽었다.
그 재조회까지 일시 실패하면 이미 봉인된 결과를 가진 Runner도 실패로 보고했다.

원래 terminal checkpoint와 상태 bytes를 이미 확보한 Runner는 Session 오류가 나면
데이터 연결과 계산을 종료하고, 같은 checkpoint의 완료 허가를 계속 조회한다.
WAITING 또는 일시적인 HTTP 실패는 허가가 아니다. 실제 FINALIZE와 정확한 checkpoint ID를
확인한 뒤에만 상태 파일을 쓰고 finalizer를 실행한다. 원래 Runner 기한·취소·현재 실행 신원
거절은 계속 적용한다. 새로운 작업이나 데이터 처리를 재개하지 않는다.

terminal receipt가 없는 계산 실패는 기존대로 실패 처리한다. 이는 누락된 grant를 추정하거나
DB에 새로 복원하는 기능이 아니며, 별도 최종 처리 재시도와 복구 CLI의 권한 검사는 유지한다.

## 검증 범위

실제 Runner subprocess·HTTPS·TLS MQTT에서 완료 응답을 보류하고 route 요청을409로
거절하는 시험을 추가한다. 응답이 돌아오면 원래 상태14의 결과를 한 번만 확정해야 한다.
그 사이 취소·완료 API의 신원 거절·WAITING 중 기한 만료·다른 checkpoint 허가에서는
finalizer 상태 파일·출력·Result를 생성하면 안 된다.

[검증 기록](../evidence/m7-stream-delayed-completion.md)에 재현 실패와 수정 뒤 결과를
분리해 기록한다. 이 경합을 고친 것으로 별도 DB/WAL 대기 원인까지 해결했다고 판정하지 않는다.
