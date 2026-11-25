# PinTrip Backend Services (S02 Friend Place Added FCM Notifications)

Backend service for PinTrip, supporting both Vercel Serverless API (Firebase Spark Plan) and Cloud Functions for Firebase (2nd gen, Blaze Plan). Written in TypeScript on Node.js 22.

---

## 1. Overview & Architecture

- **Vercel Serverless Endpoint**: `POST /api/friend-place-added` (configured via `functions/api/friend-place-added.ts` with Vercel Root Directory set to `functions`)
- **Cloud Function Trigger**: `onDocumentCreated("rooms/{roomId}/places/{placeId}")` (`functions/src/onFriendPlaceAdded.ts`)
- **Region**: `asia-northeast3` (Seoul)
- **Runtime**: Node.js 22
- **Purpose**: Sends FCM data-only push notifications to room members when a friend adds a new place pin to a shared room.

---

## 2. Local Setup & Testing

### Prerequisites
- Node.js 22.x
- npm 10+

### Installation & Build
```bash
# Navigate to functions directory
cd functions

# Install dependencies
npm install

# Compile TypeScript
npm run build
```

### Running Unit Tests
```bash
npm test
```
The test suite validates:
1. Exclusion of place author from FCM targets, title/body omission from data payload (allowing client localized strings), and distinct `recipientUid` per recipient device.
2. Atomic lease locking (`in_progress`, `leaseOwner`, 5-minute `leaseExpiresAt`) preventing duplicate sends on concurrent executions.
3. Repro A Fix: Stale execution late completion does NOT overwrite parent event status to `completed` if a delivery is `failed_transient` or in progress.
4. Repro B Fix: Mixed `success` and `in_progress` lease targets keep parent event status as `in_progress` / `failed_transient` and throw error to trigger Cloud Functions retry pipeline.
5. Distinct `eventId` generation (`evt_${roomId}_${placeId}_${cloudEventId}`) allowing separate creation events for deleted and re-added places.
6. Recovery of expired in-flight leases (`now >= leaseExpiresAt`) and stale lease owner late result rejection.
7. Error classification: permanent errors (`messaging/invalid-payload`, `invalid-argument`) marked `failed_permanent` and skipped on retry; transient errors marked `failed_transient` and retried.
8. Atomic token cleanup in transaction ONLY IF current document token matches failed token.
9. Safety checks for missing parent room, non-member author, or path `placeId` vs `kakaoPlaceId` mismatch.
10. Vercel Serverless API handler export, Method Not Allowed (405), Unauthorized (401), Forbidden (403), Bad Request (400), and Stale Place Generation (`createdAtMs`) rejection.

---

## 3. Vercel Serverless Deployment Guide (Firebase Spark Plan Compatible)

### Step 1: Vercel Project Setup
1. Log in to [Vercel](https://vercel.com) and import the repository.
2. Set **Root Directory** to `functions`.
3. Set **Node.js Version** to `22.x`.

### Step 2: Server Environment Variables
Configure the following environment variables in Vercel Dashboard -> Settings -> Environment Variables:

```
FIREBASE_PROJECT_ID=pintrip-a12a6
FIREBASE_CLIENT_EMAIL=<service-account-email>
FIREBASE_PRIVATE_KEY=<service-account-private-key>
```
*(Alternatively, supply `FIREBASE_SERVICE_ACCOUNT_KEY` with the full JSON key string).*

> [!CAUTION]
> Never place service account private keys in `secrets.properties`, client Android code, git repositories, or chat logs.

### Step 3: Deployment Command
```bash
# Deploy from root or functions directory using Vercel CLI
vercel --prod
```

### Step 4: Android App Client API URL Setup
Add the deployed Vercel domain URL to `secrets.properties` in your Android project:
```properties
NOTIFICATION_API_URL=https://<your-vercel-project>.vercel.app
```

---

## 4. Cloud Functions 2nd Gen Deployment Guide (Optional - Firebase Blaze Plan)

To deploy as a native Cloud Function (requires Blaze Plan):
```bash
firebase deploy --only functions:onFriendPlaceAdded --project pintrip-a12a6
```

---

## 5. Security & Delivery Guarantee Boundaries

1. **Service Account Keys**:
   - Never embed Firebase Admin SDK service account JSON keys inside the Android client APK. The Admin SDK runs securely inside Vercel/Cloud Functions serverless runtime.
2. **Client Token Registration**:
   - Client Android devices must be logged in and have synced FCM device tokens under `users/{uid}/devices/{installationId}`.
3. **Server Tracking Collections**:
   - `notificationEvents/{eventId}` and `notificationEvents/{eventId}/deliveries/{deliveryId}` are strictly server-side tracking collections. Client access is forbidden in `firestore.rules` (`allow read, write: if false;`).
4. **At-Least-Once Delivery Boundary**:
   - If FCM `sendEach()` succeeds but the server/database crashes before writing `status = "success"`, a subsequent retry may re-acquire the lease and resend. Client-side `eventId` LRU deduplication (`ProcessedEventCache`) on Android filters out duplicate receipt on the device.
