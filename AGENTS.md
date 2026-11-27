# Agent Tools & Rules

## Rules
- Build UI with XML layouts + ViewBinding + AppCompat + Material 3. Do not use Compose.
- Follow docs/data-contract.md for all Firestore, Gemini, and Kakao data. For Kakao places, store only the place ID and URL.
- No hardcoding:
  - Text → strings.xml
  - Colors → colors.xml or theme attributes
  - Sizes → dimens.xml
  - Text styles → `TextAppearance.Pintrip.*`
  - API keys → BuildConfig only
- Never invent placeholder data. If real data is not available yet, leave a TODO and ask.
- If you add a new library or architecture pattern, report it.
- Use ViewModel + LiveData. Keep UI state and async work (viewModelScope) in the ViewModel, use ui/common/Event for one-time messages, and do not use android:configChanges or screenOrientation.
- Load functions called from onCreate must skip if data is already loaded.

## UI work
- Read DESIGN.md before any UI work.
- Work on one screen at a time, and commit each screen separately.
- Keep current features, view IDs and screen order. Only the Behavior items in DESIGN.md change logic.
- Do not hardcode Figma's absolute positions; lay out with constraints so it fits 360–412dp phones. Do not use images from Figma.
- Refactor by modifying existing layouts, styles, drawables and strings. Create a new file only if DESIGN.md names it, and use that name. Report everything you had to create when you finish.

## Figma MCP
- Follow only the Figma link in DESIGN.md.
- If the Figma MCP is unavailable, stop and ask instead of guessing.

## Package structure
- Base package: com.pintrip.app
- Features: auth/, room/, place/, extract/, map/, notification/, travel/, vote/, plan/, expense/
  - room/: room list, create, join (including invite deep link), room home screen
  - map/: map view inside room home, map camera
  - place/: place list, place detail, likes, shake to pick
  - extract/: share receive, Gemini extraction, place matching, select places
  - notification/: friend place-added notifications (FirebaseMessagingService, NotificationMessageParser, notification channel)
  - travel/: travel mode, location foreground service
  - vote/: place voting in a room
  - plan/: Day recommendation
  - expense/: settlement calculation
- Shared: data/model/, data/repository/, data/remote/ (Kakao, Gemini), ui/common/
  - Notification permission request goes in ui/common/ (used by notification/ and travel/).

## Skills
- Shared skills are in `.agents/skills/<skill-name>/SKILL.md`.