# Hawkeye 연속 관측(MOTION) 계약

`contracts/openapi/platform-api.yaml`(tag `Scenario`)이 정본이다. 이 문서는 API·관측 서비스·UI가 같은 값을 쓰도록 요약한다.

## 버전 구분

- `hawkeye-video-safety` **v2.0.0**: task 1개 `snapshot`(profile `hawkeye-motion-snapshot` 1.0.0), 출력 포트 `frames`(application/x-tar, `edgeai.hawkeye.frames/v1`), `observations`(application/json, `edgeai.hawkeye.motion/v1`). 의존성 없음.
- **v2.1.0**: v2.0.0과 같은 DAG, profile `hawkeye-motion-snapshot` 1.1.0(`frames` 최대 32 MiB). 24 fps 미리보기의 10초 창은 JPEG 약 240장·약 16 MB(2026-10-26 측정)라 v2.0.0의 16 MiB에 걸린다. v2.0.0은 그대로 조회된다.
- v1.0.0(`capture` → `infer`)은 그대로 실행·조회된다.
- `mode`: DAG에 `snapshot` task가 있으면 `MOTION`, 아니면 `FRAMES`. `capability.versions[].mode`, `session.mode`에 있다.

## API (`/api/v1/scenarios/hawkeye-safety/video`)

| 경로 | 변경 |
|---|---|
| `GET` (capability) | `versions[].mode`, `observer`: 관측 서비스가 설정되지 않으면 `null`, 아니면 `{status: READY\|NOT_READY\|UNAVAILABLE, nodeName, pod, provider, model, error}` |
| `POST /sessions` | MOTION 버전은 관측 서비스가 READY가 아니면 503 `OBSERVER_DISABLED`/`OBSERVER_NOT_READY`, 세션 없음 |
| 세션 응답 | `mode`, `observer: {epoch, startedAt} \| null`, `endReason`에 `OBSERVER_FAILED` 추가. failureCode 예: `OBSERVER_FAILED`, `OBSERVER_EXPIRED`, `OBSERVER_STOPPED`, `OBSERVER_LOST`(관측 인스턴스 재시작), `OBSERVER_UNAVAILABLE`(임대 시간 넘게 연결 불가), `OBSERVER_SOURCE_CHANGED` |
| `GET /sessions/{id}/live` | `{sessionId, state: RUNNING\|FAILED\|EXPIRED\|STOPPED, failure: {code,message}\|null, lastError, observer: {epoch,nodeName,pod,podUid,provider,model}, stats: {processed,resets,sceneCuts,lastDetectMs}, latest: Observation\|null}`. 종료된 세션·FRAMES 세션은 409 `SESSION_NOT_LIVE`, 인스턴스가 바뀌면 409 `OBSERVER_LOST`, 응답 없음 503 `OBSERVER_UNAVAILABLE` |
| `GET /sessions/{id}/live/frames/{seq}` | 세션이 기록한 관측 인스턴스(epoch)가 보관한 최근 2,880프레임(24 fps 120초)의 JPEG(no-store). 범위 밖 404 `FRAME_NOT_FOUND`, 인스턴스가 바뀌면 409 `OBSERVER_LOST` |
| `GET /tasks/{snapshotTaskId}/frames/{seq}` | 확정된 창의 JPEG. `seq`는 관측 순번(`Observation.seq`) |
| 확정 관측 | `GET /api/v1/tasks/{snapshotTaskId}/results/observations/content` |

## Observation (live `latest`, snapshot `frames[]`)

```
{seq, frameTime, receivedAt, sha256, detectMs, width, height, gapMs|null,
 reset: null|"START"|"TIME_GAP"|"TIME_REGRESSED"|"SCENE_CUT",
 status: "NEAR_PATH"|"APPROACHING"|"UNKNOWN"|"NO_CONFLICT"|"NO_PAIR",
 tracks: [{id:"F3"|"P7", class:"forklift"|"person", visible, confidence|null, box:[x1,y1,x2,y2]|null,
           point:[x,y], path:[[t,x,y]] (t<=0 s), velocity:[vx,vy]|null, predicted:[[t,x,y]] (0.5..3.0 s)|null}],
 pairs: [{forklift, person, level:"NEAR_PATH"|"APPROACHING"|"UNKNOWN"|"NO_CONFLICT",
          reason:null|"INSUFFICIENT_HISTORY"|"TRACK_LOST", distance, minDistance|null, timeToClosest|null,
          closest:[[fx,fy],[px,py]]|null}]}
```

- 좌표는 그 프레임의 이미지 픽셀(`width`×`height`), `point`는 박스 하단 중앙. 거리는 **사람 박스 높이 배수**, 시간은 프레임 디코딩 시각 기준 초. 미터·km/h·충돌 확률이 아니며 검출 신뢰도는 충돌 확률이 아니다.
- `visible:false`: 이 프레임에서 검출되지 않은 최근 1.5초 이내 추적(박스 없음, 마지막 위치만).
- `UNKNOWN`은 판단 불가(안전 아님). `NO_PAIR`는 지게차와 사람이 함께 없음(안전 판정 아님).
- `reset`이 있는 프레임에서 추적 ID가 새로 시작한다. ID는 한 관측 인스턴스 안에서 재사용되지 않는다. `SCENE_CUT`은 32×24 흑백 축소 영상의
  이전 프레임 대비 평균 절대 차이가 30(0–255)을 넘은 프레임(다른 카메라 장면, 반복 영상의 처음)이다. 측정값: 같은 장면 연속 프레임 ≤18.4, 전환 40.6–54.6.
- `path`는 최근 4.5초 기록 중 고르게 고른 최대 10점, 속도는 최근 2초 최소제곱(0.4초 이상 기록 필요)이다. 프레임 수가 아니라 디코딩 시각으로 계산한다.

## snapshot `observations` 문서

`{apiVersion:"edgeai.hawkeye.motion/v1", framesSha256, source(cameraSource), observer:{epoch,nodeName,pod,podUid,provider,model},
window:{seq,fromSeq,toSeq,startedAt,finishedAt,evicted}, horizonSeconds:3, units, summary:{frames, byStatus, resets, tracks}, frames:[Observation]}`

창 k는 창 k-1의 마지막 프레임 바로 다음부터 시작한다(`fromSeq = 이전 toSeq + 1`). `evicted`는 창을 닫기 전에 보관 범위를 벗어난 프레임 수다.
확정 관측은 일반 결과 조회(`.../results/{port}/content`, 8 MiB 이하 JSON)로 읽는다. 24 fps 창은 사람 1명 약 0.32 MB, 6명 약 1.1 MB(2026-10-26 합성 측정)다.

## 관측 서비스 (`hawkeye-runtime/motion`, Service `edgeai-hawkeye-motion:8080`)

`GET /health`, `PUT /observers/{sessionId} {source, leaseSeconds}`(201 시작, 200 갱신, 409 `OBSERVER_BUSY`/`SOURCE_CHANGED`/`OBSERVER_<STATE>`),
`DELETE /observers/{id}`, `GET /observers/{id}/live`, `GET /observers/{id}/frames/{seq}?epoch=E`(다른 인스턴스면 409 `EPOCH_MISMATCH`, 순번은 인스턴스마다 1부터), `POST /observers/{id}/windows/{k} {minSeconds}`(202 채우는 중, 200 닫힌 범위).
API만 시작·갱신·중지하고, snapshot Task는 창 닫기·프레임 조회만 한다. 검출기는 `forklift_detector.WarehouseDetector`(TensorRT)다.
