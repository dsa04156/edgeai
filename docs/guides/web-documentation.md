# 문서 읽기와 갱신

문서는 저장소의 `docs/`와 README·PLAN·PROGRESS Markdown이 단일 원본입니다.
콘솔 사이드바의 문서 링크는 Git 저장소의 `docs/`를 엽니다.

## 기존 웹 문서 사이트

기존 Next.js Dashboard의 `/docs` 사이트(13080)는 콘솔을 Kestra 원본 Vue 앱으로 바꾸면서 소스에서 제거했습니다.
콘솔 release와 `edgeai-dashboard` 이미지에는 문서 사이트가 없습니다. 이미 실행 중인 기존 프로세스가 남아 있다면
그 화면은 당시 빌드 시점의 복사본이며 현재 문서와 다를 수 있습니다. 해당 프로세스의 교체·중지는 배포 작업에서 다룹니다.

## 목록 관리와 검증

주요 안내의 분야·순서·설명은 `scripts/internal/docs-navigation.json`에 등록합니다.
`bash scripts/test/test-docs.sh`가 등록된 원본의 존재, 제목, 상대 링크·anchor, 셸 예시 문법과 API 목록을 검사합니다.
첫 `# 제목`을 문서 제목으로 사용합니다. 파일 추가 후 [문서 작성 기준](../contributing/documentation.md)에 따라
링크와 내용·적용 범위를 검증합니다.
