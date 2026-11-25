# 입장은 앱 안의 고정 주기 스케줄러가 배치로 허용한다 (단일 인스턴스 전제)

입장 허용은 요청이 아니라 각 앱 인스턴스의 `@Scheduled` 작업(`QueueService.allowUsers`)이 3초마다 대기열 맨 앞 5명씩 처리한다(분당 100명). 뒷단 예매 서비스의 실제 여유와 연동하지 않고 고정 속도로 흘려보내는 단순한 방식을 택했다. 리더 선출이나 분산 락은 없다.

근거: 185dd3e(최초 구현), e1446da · PR #8(`LineUpAdmissionRateTooHigh` 알림), `lineup.queue.admit-interval-ms` 설정.

## Consequences

- 알려진 한계: 인스턴스를 N개 띄우면 각자 스케줄러가 돌아 입장 속도가 N배가 된다. `ZPOPMIN`이 원자적이라 같은 사람이 두 번 허용되지는 않는다. 이 상황은 막지 않고 `LineUpAdmissionRateTooHigh` 알림(분당 110명 초과)으로 감지만 한다.
- 주기나 배치 크기를 바꾸면 알림 임계값과 화면의 예상 대기 시간 계산(`index.html`의 `BATCH_SIZE`, `BATCH_INTERVAL_SEC`)도 같이 바꿔야 한다.
