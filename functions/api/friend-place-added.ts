import type { VercelRequest, VercelResponse } from "@vercel/node";
import * as admin from "firebase-admin";
import * as crypto from "crypto";
import { NotificationService } from "../src/services/notificationService";
import { PlaceDocument, RoomDocument } from "../src/types";

// Firebase Admin App Initialization using Server Environment Variables
if (admin.apps.length === 0) {
  const serviceAccountKey = process.env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (serviceAccountKey) {
    try {
      const parsedKey = JSON.parse(serviceAccountKey);
      admin.initializeApp({
        credential: admin.credential.cert(parsedKey),
      });
    } catch (e) {
      console.error("Failed to parse FIREBASE_SERVICE_ACCOUNT_KEY env var:", e);
      admin.initializeApp();
    }
  } else if (process.env.FIREBASE_PRIVATE_KEY && process.env.FIREBASE_CLIENT_EMAIL) {
    admin.initializeApp({
      credential: admin.credential.cert({
        projectId: process.env.FIREBASE_PROJECT_ID || "pintrip-a12a6",
        clientEmail: process.env.FIREBASE_CLIENT_EMAIL,
        privateKey: process.env.FIREBASE_PRIVATE_KEY.replace(/\\n/g, "\n"),
      }),
    });
  } else {
    // Default application credentials (GCP / Emulator)
    admin.initializeApp();
  }
}

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== "POST") {
    return res.status(405).json({ error: "Method Not Allowed. Expected POST." });
  }

  // 1. Authorization Header Check (Firebase ID Token)
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return res.status(401).json({ error: "Unauthorized. Missing or invalid Authorization Bearer header." });
  }

  const idToken = authHeader.substring(7).trim();
  let requestingUid: string;

  try {
    const decodedToken = await admin.auth().verifyIdToken(idToken);
    requestingUid = decodedToken.uid;
  } catch (error: any) {
    console.warn("Failed to verify Firebase ID token:", error?.message || error);
    return res.status(401).json({ error: "Unauthorized. Invalid Firebase ID token." });
  }

  // 2. Body Parsing & Input Validation
  const { roomId, notificationBatchId, kakaoPlaceId, createdAtMs } = req.body || {};
  if (!roomId || typeof roomId !== "string") {
    return res.status(400).json({ error: "Bad Request. Missing or invalid roomId." });
  }

  const executionId = crypto.randomUUID();
  const db = admin.firestore();
  const notificationService = new NotificationService({
    db,
    messaging: admin.messaging(),
  });

  try {
    // 3. Parent Room Existence and Membership Verification
    const roomRef = db.collection("rooms").doc(roomId);
    const roomSnap = await roomRef.get();
    if (!roomSnap.exists) {
      return res.status(404).json({ error: `Room ${roomId} not found.` });
    }

    const roomData = roomSnap.data() as RoomDocument;
    if (!Array.isArray(roomData.memberIds) || !roomData.memberIds.includes(requestingUid)) {
      return res.status(403).json({ error: `Forbidden. Requesting user ${requestingUid} is not a member of room ${roomId}.` });
    }

    // Path A: Batch ID Request (Primary Route)
    if (notificationBatchId && typeof notificationBatchId === "string") {
      const batchPlacesSnap = await roomRef
        .collection("places")
        .where("notificationBatchId", "==", notificationBatchId)
        .get();

      if (batchPlacesSnap.empty) {
        // Batch no longer exists in Firestore -> normal skip
        return res.status(200).json({
          success: true,
          skipped: true,
          reason: "NO_PLACES_IN_BATCH",
        });
      }

      const authorPlaces = batchPlacesSnap.docs.filter(
        (doc) => (doc.data() as PlaceDocument).addedBy === requestingUid
      );

      if (authorPlaces.length === 0) {
        // Batch exists, but requesting user is NOT the author of any places in this batch -> 403 Forbidden!
        return res.status(403).json({
          error: `Forbidden. Requesting user ${requestingUid} is not the author of batch ${notificationBatchId}.`,
        });
      }

      const result = await notificationService.processFriendPlaceAddedBatch({
        roomId,
        notificationBatchId,
        requestingUid,
        executionId,
      });

      return res.status(200).json({
        success: true,
        result,
      });
    }

    // Path B: Legacy Single Place Request (Backward Compatibility Route)
    if (kakaoPlaceId && typeof kakaoPlaceId === "string" && typeof createdAtMs === "number") {
      const placeRef = roomRef.collection("places").doc(kakaoPlaceId);
      const placeSnap = await placeRef.get();

      if (!placeSnap.exists) {
        return res.status(404).json({ error: `Place ${kakaoPlaceId} not found in room ${roomId}.` });
      }

      const placeData = placeSnap.data() as PlaceDocument;
      if (placeData.addedBy !== requestingUid) {
        return res.status(403).json({ error: "Forbidden. Requesting user is not the place author." });
      }

      const rawCreatedAt = placeData.createdAt;
      const docCreatedAtMs =
        typeof rawCreatedAt?.toMillis === "function"
          ? rawCreatedAt.toMillis()
          : typeof rawCreatedAt?.seconds === "number"
          ? rawCreatedAt.seconds * 1000
          : typeof rawCreatedAt === "number"
          ? rawCreatedAt
          : null;

      if (docCreatedAtMs !== null && docCreatedAtMs !== createdAtMs) {
        console.warn(
          `Stale place creation generation detected. Request createdAtMs: ${createdAtMs}, doc createdAtMs: ${docCreatedAtMs}. Aborting.`
        );
        return res.status(200).json({
          success: true,
          skipped: true,
          reason: "STALE_GENERATION",
        });
      }

      // If document has notificationBatchId, route through batch processor using canonical eventId
      if (placeData.notificationBatchId) {
        const overrideEventId = `evt_${roomId}_${requestingUid}_${placeData.notificationBatchId}`;
        const result = await notificationService.processFriendPlaceAddedBatch({
          roomId,
          notificationBatchId: placeData.notificationBatchId,
          requestingUid,
          executionId,
          overrideEventId,
        });

        return res.status(200).json({
          success: true,
          result,
        });
      }

      const confirmedMs = docCreatedAtMs || createdAtMs;
      const cloudEventId = `vcl_${confirmedMs}`;
      const overrideEventId = `evt_${roomId}_${requestingUid}_${kakaoPlaceId}_${confirmedMs}`;

      const result = await notificationService.processFriendPlaceAddedBatch({
        roomId,
        notificationBatchId: `single_${kakaoPlaceId}_${confirmedMs}`,
        requestingUid,
        executionId,
        fallbackPlaceId: kakaoPlaceId,
        fallbackPlaceData: placeData,
        overrideEventId,
      });

      return res.status(200).json({
        success: true,
        result,
      });
    }

    return res.status(400).json({ error: "Bad Request. Must supply notificationBatchId or kakaoPlaceId + createdAtMs." });
  } catch (error: any) {
    console.error("Error processing friend place added notification in Vercel API:", error);
    return res.status(500).json({
      error: "Internal Server Error",
    });
  }
}
