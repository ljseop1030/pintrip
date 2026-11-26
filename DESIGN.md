# Design Document

## Figma
- UI v2: https://www.figma.com/design/1J1UctgYS8BX6saiKWzRhE/Pintrip_update
- Layout, colors, sizes, icons and text come from Figma (via the Figma MCP). This file only adds what Figma cannot provide, plus the design tokens below.
- Images: use the ones in `res/drawable-nodpi/`, not images from Figma.
- Korean app: UI text in Korean via strings.xml (keep "PinTrip" and other brand names as-is).
- Screens must not break with the device in dark mode.

## Design tokens
| Token | Value | Resource |
|---|---|---|
| Primary | #4B60C0 | `colorPrimary` → `@color/pintrip_blue` (themes.xml) |
| Surface | #F3F6FE (screen background) | colors.xml |
| Title | NanumSquareRound ExtraBold, 22sp (screens), 20sp (bottom sheets, dialogs) | `TextAppearance.Pintrip.Title` |
| Body | Pretendard, 15sp | `TextAppearance.Pintrip.Body` |
| Corner | card 20dp, bottom sheet 28dp | `ShapeAppearance.Pintrip.*` |

## Fonts (Figma shows fallback fonts)
Bundle in `res/font/`.
- Pretendard (`pretendard_regular.otf`, `pretendard_semibold.otf`, `pretendard_bold.otf`): default
- NanumSquareRound ExtraBold (`nanum_square_round_eb.ttf`): one title per screen
- NanumSquareRound Bold (`nanum_square_round_b.ttf`): tagline on Start and Login

## Icons
- Room themes use Solar Bold Duotone. Import the SVG with New → Vector Asset; keep the duotone second layer as `android:fillAlpha`.

| Theme | Solar icon | Drawable |
|---|---|---|
| sea | `water-sun` | `ic_room_category_sea.xml` |
| food | `chef-hat-heart` | `ic_room_category_food.xml` |
| nature | `tree` | `ic_room_category_nature.xml` |
| city | `city` | `ic_room_category_city.xml` |
| healing (abroad) | `airplane` | `ic_room_category_healing.xml` |

- Other icons use Material Symbols Rounded.

## Screen Flow
- Login → Room list → Room home (map + place list)
- Room list → Create room / Join room (invite code or deep link)
- Room home → Place detail / Shake to pick / Vote / Day plan / Settlement / Travel mode
- Share from another app → Share receive → Select places → Confirm location → Room home

## UI v2: added / changed
### Flow
- Added: Launch → Splash → Start (logged out) → Login / Sign up. Logged in goes straight to Room list.
- Added: Room list → avatar → My info (notification switch, log out).
- Added: Room list link bar / photo button → Share receive.
- Added: Room home add button → Add place → Share receive (for this room).
- Added: Notification permission dialog before the system permission request.
- Changed: Room home invite and travel mode move from the ⋮ menu to buttons.

### Behavior
| Screen | Change |
|---|---|
| 06 Room list | Logout moves into 20 My info (avatar). Link bar and photo button start the share flow |
| 07 Create room | Theme stays "healing" during the UI migration. The rename to "abroad" is done separately later |
| 11 Room home | Invite and travel mode become buttons. `menu_room_home.xml` then has no items, so remove the menu. Add button opens 15 Add place for this room. If travel mode logic does not exist yet, build only the button states and leave a TODO |
| 12 Place detail | ⋮ removed (place delete is blocked in the current phase, data-contract §7, so nothing moves). "카카오맵에서 자세히 보기" opens the stored `kakaoPlaceUrl`; this works for `notFound` places too |
| 15 Add place | Photo uses Photo Picker |
| 16 Share receive | Add `EXTRA_PRESELECTED_ROOM_ID`. From 15 Add place, the room dropdown starts on that room. From Room list or another app, no extra |
| 17 Select places | Candidate items start unchecked. Add button is disabled while 0 are selected |
| 18 Confirm location | "제외" removed. To exclude a place, go back and leave it unchecked in 17 |
| 21 Notification permission | Shown before the system request when `POST_NOTIFICATIONS` is not granted |

### Motion
- Splash → Start (logged out): logo moves up from the center, then background, tagline and buttons fade in one after another. The SplashScreen theme only shows the icon, so this motion is implemented in `activity_main.xml`, not in the splash theme.
- Travel mode button (11 → 11-C → 11-B): shrunk icon → tap to extend "여행 모드 켜기" → tap to start (11-B) → tap again to turn off (confirm first).

## Frame ↔ file
| Figma | File |
|---|---|
| 01 Splash | themes.xml (SplashScreen theme) |
| 02 Start | activity_main.xml |
| 03 Login | activity_test_login.xml |
| 04 Sign up | activity_sign_up.xml |
| 05 · 06 Room list | activity_room_list.xml, item_room_card.xml |
| 07 Create room | activity_create_room.xml |
| 08 Invite code | dialog_invite_code_display.xml |
| 09 Join by code | dialog_join_code.xml |
| 10 Join success | dialog_join_success.xml |
| 11 · 11-B · 11-C Room home | activity_room_map.xml, item_place.xml |
| 12 Place detail | activity_place_detail.xml |
| 13 · 14 Shake to pick | bottom_sheet_shake_to_pick.xml |
| 15 Add place | bottom_sheet_add_place.xml (new bottom sheet) |
| 16 Share receive | activity_share_receive.xml |
| 17 Select places | activity_select_places.xml, item_select_place.xml |
| 18 Confirm location | activity_confirm_location.xml, item_confirm_candidate.xml |
| 19 Manual search | fragment_manual_search_bottom_sheet.xml |
| 20 My info | bottom_sheet_my_info.xml (new bottom sheet) |
| 21 Notification permission | dialog_notification_permission.xml (new dialog) |