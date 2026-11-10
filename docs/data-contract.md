# PinTrip Data Contract

Single source of truth for Firestore schema, in-memory models, and the Gemini/Kakao formats.

---

## 1. Rules

1. **Never store Kakao Local API response data** (place name, address, coordinates, category, phone) anywhere: Firestore, Room, SharedPreferences, or files. Re-fetch when a screen opens and keep it in ViewModel memory only.
2. The only Kakao data we store: **place ID and place URL**.
3. Re-fetch = keyword search with the stored `searchKeyword` (+ region), then pick the result whose `id == kakaoPlaceId`. Kakao has no lookup-by-ID API.
4. `searchKeyword` is the name Gemini extracted or the query the user typed. Never overwrite it with Kakao's `place_name`.
5. Regions we may store: the room region, a region the user typed, a region Gemini extracted. Never store a region cut out of a Kakao address.
6. No screenshot storage.
7. Store only places the user confirmed. Candidates, excluded items, and NOT_FOUND items are never stored.

---

## 2. Collections

```
users/{uid}
rooms/{roomId}
rooms/{roomId}/places/{kakaoPlaceId}
inviteCodes/{code}
```

There is no `likes` collection. Likes live in each place's `likedBy` array.

---

## 3. Firestore fields

### users/{uid}  (doc ID = Firebase Auth uid)

| Field | Type | Req | Notes |
|---|---|---|---|
| nickname | String | ✓ | From sign-up |
| email | String | ✓ | |
| avatarColor | String | ✓ | `#RRGGBB`, random at sign-up |
| createdAt | Timestamp | ✓ | |

### rooms/{roomId}  (doc ID = auto ID)

| Field | Type | Req | Notes |
|---|---|---|---|
| name | String | ✓ | "Busan" |
| region | String | ✓ | Korean region name: "서울", "부산", "제주", "강릉", "경주", "여수" 등 |
| startDate | Timestamp? | | null → "Date to be determined" |
| endDate | Timestamp? | | |
| icon | String | ✓ | `sea` / `food` / `nature` / `city` / `healing` |
| inviteCode | String | ✓ | 6 chars, uppercase + digits, avoid 0/O/1/I |
| ownerId | String | ✓ | Creator uid |
| memberIds | List\<String> | ✓ | For `whereArrayContains("memberIds", uid)` |
| members | Map\<uid, Member> | ✓ | Denormalized for avatars |
| placeCount | Int | ✓ | `FieldValue.increment` on add/delete |
| createdAt | Timestamp | ✓ | |
| updatedAt | Timestamp | ✓ | Room list sort |

`Member = { nickname: String, avatarColor: String, lastSeenAt: Timestamp? }`
`lastSeenAt` is used for the "NEW n" badge and friend notifications. Update it on entering room home.

### rooms/{roomId}/places/{kakaoPlaceId}  (doc ID = Kakao place ID, prevents duplicates per room)

| Field | Type | Req | Source | Notes |
|---|---|---|---|---|
| kakaoPlaceId | String | ✓ | Kakao | Same as doc ID |
| kakaoPlaceUrl | String | ✓ | Kakao | "Open in KakaoMap", directions |
| searchKeyword | String | ✓ | Gemini / user | Re-fetch query |
| searchRegion | String? | | Gemini / user | Narrower than room region ("해운대"). If null, use room region. Never filled from a Kakao address |
| matchType | String | ✓ | App | `auto` / `confirmed` / `manual` |
| sourceType | String | ✓ | App | `screenshot` / `link` / `text` |
| sourceUrl | String? | | User | Original URL for links |
| addedBy | String | ✓ | App | uid |
| addedByNickname | String | ✓ | App | "Added by Minji" |
| likedBy | List\<String> | ✓ | App | Starts empty |
| likeCount | Int | ✓ | App | Always equals `likedBy.size`. For "Most liked" sort |
| createdAt | Timestamp | ✓ | App | |

### inviteCodes/{code}  (doc ID = invite code)

| Field | Type | Req |
|---|---|---|
| roomId | String | ✓ |
| createdAt | Timestamp | ✓ |

Separate collection so non-members never need read access to `rooms`, and duplicate checks are a single doc-exists check.

---

## 4. In-memory model: PlaceDetail (never stored)

Repository merges `Place` (Firestore) + live Kakao search result.

| Field | Type | Source |
|---|---|---|
| place | Place | Firestore |
| status | String | `found` / `notFound` |
| name | String? | Kakao `place_name` |
| address | String? | Kakao `road_address_name`, fallback `address_name` |
| lat | Double? | Kakao `y` (String → Double) |
| lng | Double? | Kakao `x` (String → Double) |
| category | String? | Mapped from `category_group_code` |
| phone | String? | Kakao `phone` (for `ACTION_DIAL`) |

**Category mapping:** `FD6` → `dining`, `CE7` → `cafe`, `AT4` / `CT1` → `sights`, `AD5` → `stay`, anything else → `etc`

**notFound:** show `searchKeyword` as the name, list only (no map pin), only "Open in KakaoMap" enabled.

Category filter and shake-to-pick run on the in-memory `PlaceDetail` list, not Firestore queries.

---

## 5. Write operations

| Action | How |
|---|---|
| Sign up | Create Auth user → set `users/{uid}` |
| Create room | Batch: set `rooms/{newId}` + set `inviteCodes/{code}`. Regenerate code if it exists |
| Join room | Get `inviteCodes/{code}` → update `rooms/{roomId}`: `memberIds` arrayUnion(uid), set `members.{uid}` |
| Add places | Transaction per selected room: **read all** `places/{kakaoPlaceId}` first, then set only missing ones + `placeCount` increment(1). Skip existing IDs |
| Like toggle | Transaction: get place → if uid not in `likedBy`: arrayUnion + `likeCount` +1, else arrayRemove + `likeCount` -1 |
| Delete place | **Only if `addedBy == my uid`** (hide menu otherwise). Batch: delete place + `placeCount` -1. Likes go with it; the place can be re-added |
| Enter room home | `members.{uid}.lastSeenAt` = serverTimestamp (low priority) |

---

## 6. Queries

| Screen | Query | Index |
|---|---|---|
| My rooms | `rooms` where `memberIds` array-contains uid, orderBy `updatedAt` desc | Composite (create via console error link) |
| Place list (newest) | `places` orderBy `createdAt` desc | Auto |
| Place list (most liked) | `places` orderBy `likeCount` desc | Auto |
| Invite code check | get `inviteCodes/{code}` | None |

---

## 7. Security rules (draft)

- `users/{uid}`: signed-in users read; owner writes.
- `rooms/{roomId}`: members (`memberIds`) read/write. Allow the update that adds your own uid to `memberIds` when joining.
- `rooms/{roomId}/places/{placeId}`: parent room members read/create/update. Delete only if `resource.data.addedBy == request.auth.uid`.
- `inviteCodes/{code}`: signed-in users `get` only. No `list`.

---

## 8. Gemini extraction

- One call per screenshot (max 5). A link = one call with the page text. Shared text without a URL = one call with the text itself.
- Firebase AI Logic structured output: `responseMimeType = "application/json"` + `responseSchema`. No regex parsing.
- `temperature = 0`. Language: Korean (Kakao search).

### Response

```json
{
  "places": [
    {
      "name": "모모스커피",
      "region": "부산 금정구",
      "categoryHint": "cafe",
      "rawText": "3. Momos Coffee",
      "outsideRoomRegion": false
    }
  ],
  "regionHint": "부산"
}
```

| Field | Type | Req | Notes |
|---|---|---|---|
| places | Array | ✓ | Empty if none |
| places[].name | String | ✓ | Kakao search query. Keep branch names ("이재모피자 본점"). Strip numbering, emoji, quotes |
| places[].region | String? | | Region written near the place in the image. null if absent. No guessing |
| places[].categoryHint | String? | | `dining` / `cafe` / `sights` / `stay` / `etc`. Helper only |
| places[].rawText | String | ✓ | Original text in the image. Shown as "Recognized text" |
| places[].outsideRoomRegion | Boolean | ✓ | true only with clear evidence the place is outside the room region |
| regionHint | String? | | Region context of the whole image |

### Prompt rules

- Extract only shop/attraction names actually written in the image. Never invent places.
- Do not extract addresses, menu items, hashtags, person names, or a bare region name ("부산").
- Deduplicate repeated places.
- Include the room region in the prompt ("This trip room's region is {region}."). Do not fill `region` with the room region.
- `outsideRoomRegion`: true only with evidence ("제주" in a Busan room). Inside the room region or no clue → false.

### Error handling

| Case | Handling |
|---|---|
| JSON parse fail / call fail | Retry once → "Couldn't find places" popup + manual search button |
| `places` empty | Same popup |
| Same `name` across screenshots | Merge (ignore spaces/case), match once |
| Room/extracted region mismatch | See §9 Region mismatch |

### Search strategy

> For now we search with a region-prefixed query instead of coordinates. If integration shows we need another method, update this section and §9 Re-fetch.

- `outsideRoomRegion == false`: query = `"{room region} {region} {name}"` (`region` only if present).
- `outsideRoomRegion == true`: query = `"{region or regionHint} {name}"`.
- If `categoryHint` is set, also pass `category_group_code`: dining → `FD6`, cafe → `CE7`, sights → `AT4`, stay → `AD5`.

---

## 9. Match result (memory only, never stored)

One Kakao keyword search per Gemini place. Passed to the Select Places and Confirm Location screens. Candidate names/addresses/coordinates are live Kakao data and are dropped after saving.

```json
{
  "localId": "img0_p2",
  "extracted": { "name": "모모스커피", "region": null, "categoryHint": "cafe", "rawText": "3. Momos Coffee" },
  "status": "CHECK",
  "candidates": [
    { "kakaoPlaceId": "12345", "kakaoPlaceUrl": "https://place.map.kakao.com/12345", "name": "모모스커피 본점", "address": "부산 금정구 오시게로 20", "lat": 35.22, "lng": 129.08, "category": "cafe" }
  ],
  "selectedPlaceId": null,
  "regionMismatch": false,
  "checked": true
}
```

| Field | Type | Notes |
|---|---|---|
| localId | String | Screen-only ID: `img{screenshotIndex}_p{order}` |
| extracted | GeminiPlace | The `places[]` item from §8 |
| status | String | `MATCHED` / `CHECK` / `NOT_FOUND` |
| candidates | Array | Max 5. Same fields as `PlaceDetail` |
| selectedPlaceId | String? | Auto for MATCHED, user choice for CHECK |
| regionMismatch | Boolean | Outside the room region |
| checked | Boolean | Checkbox. NOT_FOUND: false and disabled. `regionMismatch`: starts false, user may check |

### Status rules

| Search result | status |
|---|---|
| 0 results (after one retry without category) | `NOT_FOUND` |
| 1 result | `MATCHED` |
| 2+ with exactly one exact name match (ignore spaces/case) | `MATCHED` |
| 2+ otherwise | `CHECK` (top 5 as candidates) |
| No region hint at all and too many results | Ask the user for a region before searching |

### Saving: MatchResult → Place

| Place field | Value |
|---|---|
| kakaoPlaceId / kakaoPlaceUrl | From the selected candidate |
| searchKeyword | `extracted.name` (never the Kakao name). For `manual`, the user's query |
| searchRegion | `extracted.region` (never from the candidate address). If outside room region and null, use `regionHint` |
| matchType | MATCHED → `auto`, chosen in CHECK → `confirmed`, manual search → `manual` |
| sourceType | Screenshot → `screenshot`, text with a URL → `link`, text without a URL → `text` |
| sourceUrl | The URL in the shared text (`link` only), otherwise null |

### Region mismatch

`regionMismatch = true` if either:
1. Before search: Gemini `outsideRoomRegion == true`
2. After search: the selected candidate's live address is outside the room region (compare in memory only)

UI: on entering Select Places, show a popup "n places are outside {room region}". Mark those items with a warning badge and start them unchecked. The user can still check and add them.

Re-fetch after saving: search with the same query rule as §8 using `searchKeyword` and `searchRegion`, then pick the result whose `id == kakaoPlaceId`. So outside-region places must have `searchRegion` set.

### status vs matchType

- `status`: how to show it on screen now. Memory only.
- `matchType`: how a saved place was confirmed. Stored in Firestore.

### Note
- If your feature needs new data (collections, fields, models, Gemini/Kakao formats, writes, queries, or rules), add it here before coding.