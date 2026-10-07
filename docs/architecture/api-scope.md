# API 영역과 책임

현재 관리 endpoint와 개수는 계약에서 생성한 [API 참고](../reference/api.md)를 기준으로 합니다.
과거 설계 초안·단계별 operation 개수는 현재 API 목록이 아닙니다.

## 관리 API

Spring API는 프로필, 앱 장치, 노드·센서 관측, 가상 장치, DAG와 실행 결과, 감사 기록을 제공합니다.
등록·발행과 실행 제어는 별도의 유스케이스이며 내부 실행 주체의 heartbeat·claim을 공개 관리 동작으로 만들지 않습니다.

## 배포 API

Platform-Service는 DDS 편집기의 빌드·Git 저장·Argo 배포를 처리합니다.
Spring Run 생성이나 Result commit으로 변환되지 않습니다. [워크플로 모델](../concepts/workflows.md)을 확인합니다.

## 내부 계약

Runner·VD·Device stream·참조 Remote는 별도의 OpenAPI와 인증·수명 계약을 사용합니다.
TaskAttempt 생성은 재시도·전환 정책이 관리하며 사용자가 임의로 생성하는 API가 아닙니다.
외부 제공자와 실장비 수용은 구현된 참조 계약과 구분합니다.
