# Agent Tools & Rules

## Rules
- Build UI with XML layouts + ViewBinding + AppCompat + Material 3. Do not use Compose.
- For UI work, follow only the Figma link in DESIGN.md (call the Figma MCP at most once per prompt, ask if you need more).
- Follow docs/data-contract.md for all Firestore, Gemini, and Kakao data. For Kakao places, store only the place ID and URL.
- No hardcoding: strings in strings.xml, colors in colors.xml, API keys from BuildConfig only.
- Never invent placeholder data. If real data is not available yet, leave a TODO and ask.
- If you add a new library or architecture pattern, report it.
- Use ViewModel + LiveData. Keep UI state and async work (viewModelScope) in the ViewModel, use ui/common/Event for one-time messages, and do not use android:configChanges or screenOrientation.
- Load functions called from onCreate must skip if data is already loaded.

## Package structure
- Base package: com.pintrip.app
- Features: auth/, room/, place/, extract/, map/, notification/, travel/, vote/, plan/, expense/
  - room/: room list, create, join (including invite deep link), room home screen
  - map/: map view inside room home, map camera
  - place/: place list, place detail, likes, shake to pick
  - extract/: share receive, Gemini extraction, place matching, select places
  - notification/: friend place-added notifications (Firestore listener, WorkManager worker)
  - travel/: travel mode, location foreground service
  - vote/: place voting in a room
  - plan/: Day recommendation
  - expense/: settlement calculation
- Shared: data/model/, data/repository/, data/remote/ (Kakao, Gemini), ui/common/
  - Notification permission request goes in ui/common/ (used by notification/ and travel/).

## Skills
- Shared skills are in `.agents/skills/<skill-name>/SKILL.md`.