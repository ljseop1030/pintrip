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
- Trigger: Device shake sensor (가속도 센서)
- Confirmed Policies:
  - Operates on the in-memory `PlaceDetail` list of the current room.
  - Only `status == "found"` places are eligible candidates. `notFound` places are excluded from selection (per Data Contract §4: list display and external KakaoMap opening only).
  - Category filtering is applied in memory before selection.
  - Default selection behavior is independent random selection (consecutive selection of the same place is allowed; open for team review).
- Unconfirmed / Pending:
  - Result UI display format (dedicated screen / Dialog / BottomSheet).
  - Map pin highlighting and camera movement upon selection.
  - Alternative trigger for devices without accelerometer sensor (e.g., action menu or touch gesture).
