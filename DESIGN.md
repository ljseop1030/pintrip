# Design Document

## Design System
- Figma: https://www.figma.com/design/oIdyV2GxAUrb21NNrUPpVA/%EB%AA%A8%ED%94%84-%ED%8C%80%ED%94%8C?node-id=43-2
- This is a Korean based app. Write UI text in Korean (keep the app name (PinTrip) and other brand names as-is)
- Take colors from the Figma design, reuse colors that already exists in colors.xml

## Screen Flow
- Login → Room list → Room home (map + place list)
- Room list → Create room / Join room (invite code or deep link)
- Room home → Place detail / Shake to pick / Vote / Day plan / Settlement / Travel mode
- Share from another app → Share receive → Select places → Confirm location → Room home

### Shake to pick (흔들어서 고르기) Flow
- Entry point: Room home (방 홈)
- Trigger: Device shake sensor (기기 흔들기 센서)
- 1차 로직 구현 (Current Phase):
  - 실제 방의 `PlaceDetail` 메모리 목록 기반 카테고리 필터링 (`status == "found"` 항목 대상).
  - 독립 무작위 추첨 (동일 장소 연속 선택 허용).
  - g-force 정규화 가속도 센서 감지 (`ShakeDetector`) 및 `ShakeToPickViewModel` UI 상태 관리.
  - 현재 구현 상태: Activity/XML/메뉴/Manifest 및 지도·방 홈 화면은 추가하지 않았으며, 기존 장소 클릭 시 외부 카카오맵 열기 동작 유지.
- 후속 UI 통합 연결 (Follow-up Phase):
  - 혜원의 방 홈 UI 준비 시 실제 `roomId` 전달, 카테고리 선택 UI 연결, 화면 생명주기(`onResume`/`onPause`)와 센서 `start()`/`stop()` 연결 및 추첨 결과 표시 통합.
- Unconfirmed / Pending / Proposal (미확정 및 제안 사항):
  - 전용 화면/다이얼로그/BottomSheet 표시 형태 및 지도 핀 강조·카메라 이동 여부는 합의되지 않은 미확정 상태.
  - 센서 미지원 기기용 대체 진입 동작(Action menu / 터치 제스처): 제안/디자인 승인 대기.