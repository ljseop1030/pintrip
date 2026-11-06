# Agent Tools & Rules

## Rules
- Build UI with XML layouts + AppCompat + Material 3. Do not use Compose.
- For UI work, follow only the Figma link in DESIGN.md.
- Follow docs/data-contract.md for all Firestore, Gemini, and Kakao data.
- Do not store Kakao search results (place name, address, coordinates). Store only the place ID and URL.
- Put strings in strings.xml and colors in colors.xml. No hardcoding.
- Read API keys only from BuildConfig. Never write keys in code.
- Modify only the files you are asked to change.

## Package structure
- Base package: com.pintrip.app
- Features: auth/, room/, place/, extract/, map/
    - room/: room list, create, join, room home screen
    - map/: map view inside room home
    - place/: place list, place detail, likes
    - extract/: share receive, Gemini extraction, place matching, select places
- Shared: data/model/, data/repository/, data/remote/ (Kakao, Gemini), data/mock/, ui/common/
- Code used by one feature goes in that feature package. Code used by two or more features goes in Shared.

## Skills
- Shared skills are in `.agents/skills/<skill-name>/SKILL.md`.