# Everyday development commands; explicit operational scripts remain under scripts/ops.
.DEFAULT_GOAL := help
.PHONY: help setup up down backend dashboard storage db-start db-stop check health test
help:
	@printf '%s\n' 'make setup       개발 도구·.env 준비' 'make up          PostgreSQL·MQTT 시작 (Compose)' 'make down        개발 컨테이너 종료, 볼륨 유지' 'make backend     Spring Boot 실행' 'make dashboard   Next.js 실행' 'make platform-service  원본 워크플로 배포 API 실행' 'make storage     MinIO 시작' 'make db-start    Docker 없이 로컬 PostgreSQL 시작' 'make db-stop     로컬 PostgreSQL 종료' 'make check       개발 환경 점검' 'make health      실행 중인 DB/API/화면 연결 확인' 'make test        기본 단위 테스트'
setup:
	bash scripts/dev/bootstrap.sh
up:
	bash scripts/dev/dev-up.sh
down:
	bash scripts/dev/dev-down.sh
backend:
	bash scripts/dev/dev-backend.sh
dashboard:
	bash scripts/dev/dev-dashboard.sh
storage:
	bash scripts/dev/dev-storage.sh
db-start:
	bash scripts/dev/dev-postgres-local.sh start
db-stop:
	bash scripts/dev/dev-postgres-local.sh stop
check:
	bash scripts/dev/preflight.sh local
health:
	bash scripts/test/test-health.sh
test:
	bash scripts/test/test-unit.sh

.PHONY: platform-service
platform-service:
	bash scripts/dev/dev-platform-service.sh
