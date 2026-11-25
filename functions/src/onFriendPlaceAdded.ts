import { onDocumentCreated } from "firebase-functions/v2/firestore";
import * as admin from "firebase-admin";
import * as crypto from "crypto";
import { PlaceDocument } from "./types";
import { NotificationService } from "./services/notificationService";

if (admin.apps.length === 0) {
  admin.initializeApp();
}

/**
 * rooms/{roomId}/places/{placeId} 새 문서 생성 시 트리거됩니다.
 * 방의 다른 멤버들에게 FCM 장소 추가 알림을 전송합니다.
 */
export const onFriendPlaceAdded = onDocumentCreated(
  {
    document: "rooms/{roomId}/places/{placeId}",
    region: "asia-northeast3",
    retry: true, // 일시 실패 시 Cloud Functions 재시도 활성화
  },
  async (event) => {
    const snap = event.data;
    if (!snap) {
      console.warn("No snapshot data in event. Skipping.");
      return;
    }

    const roomId = event.params.roomId;
    const placeId = event.params.placeId;
    const cloudEventId = event.id;
    const executionId = crypto.randomUUID();
    const placeData = snap.data() as PlaceDocument;

    console.log(`Triggered onFriendPlaceAdded for room: ${roomId}, place: ${placeId}, cloudEventId: ${cloudEventId}, executionId: ${executionId}`);

    const notificationService = new NotificationService({
      db: admin.firestore(),
      messaging: admin.messaging(),
    });

    try {
      const result = await notificationService.processFriendPlaceAdded(
        roomId,
        placeId,
        cloudEventId,
        placeData,
        executionId
      );
      console.log(`Finished processing onFriendPlaceAdded:`, result);
    } catch (error) {
      console.error(`Error processing onFriendPlaceAdded for event ${cloudEventId}:`, error);
      throw error; // Re-throw so Cloud Functions runtime retries the event
    }
  }
);
