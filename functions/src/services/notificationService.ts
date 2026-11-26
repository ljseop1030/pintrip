import * as admin from "firebase-admin";
import * as crypto from "crypto";
import {
  PlaceDocument,
  RoomDocument,
  RecipientDeviceTarget,
  DeliveryRecord,
} from "../types";

export interface NotificationServiceOptions {
  db: admin.firestore.Firestore;
  messaging: admin.messaging.Messaging;
}

export interface ProcessNotificationResult {
  eventId: string;
  totalRecipients: number;
  totalDevices: number;
  succeededDevices: number;
  failedDevices: number;
  skippedAlreadySucceeded: number;
  skippedInFlight: number;
}

export interface BatchNotificationParams {
  roomId: string;
  notificationBatchId: string;
  requestingUid: string;
  executionId?: string;
  fallbackPlaceId?: string;
  fallbackPlaceData?: PlaceDocument;
  overrideEventId?: string;
}

const PERMANENT_ERROR_CODES = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
  "messaging/invalid-argument",
  "messaging/invalid-payload",
  "messaging/invalid-recipient",
  "messaging/payload-size-exceeded",
  "messaging/sender-id-mismatch",
  "messaging/mismatched-credential",
  "messaging/project-not-found",
]);

const TRANSIENT_ERROR_CODES = new Set([
  "messaging/internal-error",
  "messaging/server-unavailable",
  "messaging/quota-exceeded",
  "messaging/device-message-rate-exceeded",
  "messaging/topics-message-rate-exceeded",
  "messaging/unavailable",
]);

export class NotificationService {
  private db: admin.firestore.Firestore;
  private messaging: admin.messaging.Messaging;

  constructor(options: NotificationServiceOptions) {
    this.db = options.db;
    this.messaging = options.messaging;
  }

  /**
   * 상위 notificationEvents 문서의 상태를 자식 Deliveries 문서의 실시간 상태에 기반하여 트랜잭션으로 원자적 업데이트합니다.
   * 트랜잭션 내 모든 읽기(get)를 쓰기(set/update)보다 먼저 수행하며 createdAt 시각을 보존합니다.
   */
  private async syncParentEventStatus(
    eventRef: admin.firestore.DocumentReference,
    deliveriesRef: admin.firestore.CollectionReference,
    eventId: string,
    roomId: string,
    kakaoPlaceId: string,
    addedBy: string
  ): Promise<{ eventStatus: string; isAllTerminal: boolean }> {
    return this.db.runTransaction(async (transaction) => {
      // 1. ALL READS FIRST
      const eventSnap = await transaction.get(eventRef);
      const deliveriesSnap = await transaction.get(deliveriesRef);

      let allTerminal = true;
      let anyTransient = false;

      deliveriesSnap.docs.forEach((doc) => {
        const delData = doc.data() as DeliveryRecord;
        if (
          delData.status === "pending" ||
          delData.status === "in_progress" ||
          delData.status === "failed_transient"
        ) {
          allTerminal = false;
        }
        if (delData.status === "failed_transient") {
          anyTransient = true;
        }
      });

      const eventStatus = allTerminal
        ? "completed"
        : anyTransient
        ? "failed_transient"
        : "in_progress";

      // 2. ALL WRITES AFTER READS
      if (!eventSnap.exists) {
        transaction.set(eventRef, {
          eventId,
          roomId,
          kakaoPlaceId,
          addedBy,
          status: eventStatus,
          createdAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
      } else {
        transaction.update(eventRef, {
          status: eventStatus,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
      }

      return { eventStatus, isAllTerminal: allTerminal };
    });
  }

  /**
   * 저장 배치 ID (notificationBatchId) 또는 단일 placeId 단위로 장소 생성 알림을 발송합니다.
   */
  async processFriendPlaceAddedBatch(
    params: BatchNotificationParams
  ): Promise<ProcessNotificationResult> {
    const {
      roomId,
      notificationBatchId,
      requestingUid,
      executionId = crypto.randomUUID(),
      fallbackPlaceId,
      fallbackPlaceData,
      overrideEventId,
    } = params;

    const eventId = overrideEventId || `evt_${roomId}_${requestingUid}_${notificationBatchId}`;

    if (!roomId || !requestingUid || (!notificationBatchId && !fallbackPlaceId)) {
      console.warn("Invalid notification batch input parameters. Aborting.");
      return {
        eventId,
        totalRecipients: 0,
        totalDevices: 0,
        succeededDevices: 0,
        failedDevices: 0,
        skippedAlreadySucceeded: 0,
        skippedInFlight: 0,
      };
    }

    // 1. 부모 방 문서 존재 및 멤버십 검증
    const roomDocRef = this.db.collection("rooms").doc(roomId);
    const roomSnap = await roomDocRef.get();
    if (!roomSnap.exists) {
      console.warn(`Parent room ${roomId} does not exist. Aborting notification.`);
      return {
        eventId,
        totalRecipients: 0,
        totalDevices: 0,
        succeededDevices: 0,
        failedDevices: 0,
        skippedAlreadySucceeded: 0,
        skippedInFlight: 0,
      };
    }

    const roomData = roomSnap.data() as RoomDocument;
    if (!Array.isArray(roomData.memberIds) || !roomData.memberIds.includes(requestingUid)) {
      console.warn(`Requesting user ${requestingUid} is not a valid member of room ${roomId}. Aborting.`);
      return {
        eventId,
        totalRecipients: 0,
        totalDevices: 0,
        succeededDevices: 0,
        failedDevices: 0,
        skippedAlreadySucceeded: 0,
        skippedInFlight: 0,
      };
    }

    // 2. 해당 batchId 또는 fallbackPlaceId로 장소 문서들 조회 및 작성자 검증
    let rawPlaceDocs: { id: string; data: PlaceDocument }[] = [];
    if (notificationBatchId) {
      const placesSnap = await roomDocRef
        .collection("places")
        .where("notificationBatchId", "==", notificationBatchId)
        .get();
      rawPlaceDocs = placesSnap.docs.map((doc) => ({ id: doc.id, data: doc.data() as PlaceDocument }));
    }

    if (rawPlaceDocs.length === 0 && fallbackPlaceId) {
      const singleSnap = await roomDocRef.collection("places").doc(fallbackPlaceId).get();
      if (singleSnap.exists) {
        rawPlaceDocs = [{ id: singleSnap.id, data: singleSnap.data() as PlaceDocument }];
      } else if (fallbackPlaceData) {
        rawPlaceDocs = [{ id: fallbackPlaceId, data: fallbackPlaceData }];
      }
    }

    // 작성자가 일치하는 장소만 엄격하게 필터링 (혼합 작성자 오염 차단)
    const placeDataList = rawPlaceDocs.filter((p) => p.data.addedBy === requestingUid);

    if (placeDataList.length === 0) {
      console.warn(`No valid places matching author ${requestingUid} found for notificationBatchId ${notificationBatchId} in room ${roomId}. Aborting.`);
      return {
        eventId,
        totalRecipients: 0,
        totalDevices: 0,
        succeededDevices: 0,
        failedDevices: 0,
        skippedAlreadySucceeded: 0,
        skippedInFlight: 0,
      };
    }

    const firstPlaceData = placeDataList[0].data;
    const addedCount = placeDataList.length;
    const addedByNickname = firstPlaceData.addedByNickname || "";
    const roomName = roomData.name || "";
    const kakaoPlaceId = addedCount === 1 ? placeDataList[0].id : "";

    // 3. 수신 대상 선정 (작성자 제외, 중복 없는 memberIds)
    const recipientUids = Array.from(
      new Set(roomData.memberIds.filter((uid) => uid && uid !== requestingUid))
    );

    if (recipientUids.length === 0) {
      console.log(`No recipient members found for room ${roomId} (excluding author).`);
      return {
        eventId,
        totalRecipients: 0,
        totalDevices: 0,
        succeededDevices: 0,
        failedDevices: 0,
        skippedAlreadySucceeded: 0,
        skippedInFlight: 0,
      };
    }

    // 4. 각 수신자의 기기 토큰 수집
    const targets: RecipientDeviceTarget[] = [];
    for (const recipientUid of recipientUids) {
      const devicesSnap = await this.db
        .collection("users")
        .doc(recipientUid)
        .collection("devices")
        .get();

      const seenTokens = new Set<string>();
      for (const deviceDoc of devicesSnap.docs) {
        const token = deviceDoc.data()?.token;
        const platform = deviceDoc.data()?.platform;
        const installationId = deviceDoc.id;

        if (
          token &&
          typeof token === "string" &&
          token.trim().length > 0 &&
          platform === "android" &&
          !seenTokens.has(token)
        ) {
          seenTokens.add(token);
          targets.push({
            recipientUid,
            installationId,
            token: token.trim(),
            deliveryId: `${recipientUid}_${installationId}`,
          });
        }
      }
    }

    if (targets.length === 0) {
      console.log(`No valid Android device tokens found for recipients in room ${roomId}.`);
      return {
        eventId,
        totalRecipients: recipientUids.length,
        totalDevices: 0,
        succeededDevices: 0,
        failedDevices: 0,
        skippedAlreadySucceeded: 0,
        skippedInFlight: 0,
      };
    }

    // 5. 원자적 2단계 처리 권한(Lease) 획득 Phase
    const eventRef = this.db.collection("notificationEvents").doc(eventId);
    const deliveriesRef = eventRef.collection("deliveries");

    const LEASE_DURATION_MS = 5 * 60 * 1000; // 5분
    const now = Date.now();

    const acquiredTargets: RecipientDeviceTarget[] = [];
    let skippedAlreadySucceeded = 0;
    let skippedInFlight = 0;

    for (const target of targets) {
      const deliveryDocRef = deliveriesRef.doc(target.deliveryId);

      const acquireResult = await this.db.runTransaction(async (transaction) => {
        const docSnap = await transaction.get(deliveryDocRef);
        if (!docSnap.exists) {
          transaction.set(deliveryDocRef, {
            deliveryId: target.deliveryId,
            recipientUid: target.recipientUid,
            installationId: target.installationId,
            status: "in_progress",
            leaseOwner: executionId,
            leaseExpiresAt: now + LEASE_DURATION_MS,
            retryCount: 0,
            errorCode: null,
            updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          });
          return "ACQUIRED";
        }

        const data = docSnap.data() as DeliveryRecord;
        if (data.status === "success" || data.status === "failed_permanent") {
          return "TERMINAL";
        }

        if (
          (data.status === "in_progress" || data.status === "failed_transient") &&
          data.leaseExpiresAt &&
          now < data.leaseExpiresAt
        ) {
          return "IN_FLIGHT";
        }

        // pending or expired lease -> acquire lease
        transaction.update(deliveryDocRef, {
          status: "in_progress",
          leaseOwner: executionId,
          leaseExpiresAt: now + LEASE_DURATION_MS,
          retryCount: (data.retryCount || 0) + 1,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
        return "ACQUIRED";
      });

      if (acquireResult === "ACQUIRED") {
        acquiredTargets.push(target);
      } else if (acquireResult === "TERMINAL") {
        const termSnap = await deliveriesRef.doc(target.deliveryId).get();
        if (termSnap.data()?.status === "success") {
          skippedAlreadySucceeded++;
        }
      } else if (acquireResult === "IN_FLIGHT") {
        skippedInFlight++;
      }
    }

    if (acquiredTargets.length === 0) {
      console.log(`No pending targets acquired for event ${eventId}. Skipped ${skippedAlreadySucceeded} terminal, ${skippedInFlight} in-flight.`);

      const parentSync = await this.syncParentEventStatus(
        eventRef,
        deliveriesRef,
        eventId,
        roomId,
        kakaoPlaceId,
        requestingUid
      );

      if (skippedInFlight > 0 || !parentSync.isAllTerminal) {
        throw new Error(`Event ${eventId} has in-flight or unfulfilled targets.`);
      }

      return {
        eventId,
        totalRecipients: recipientUids.length,
        totalDevices: targets.length,
        succeededDevices: skippedAlreadySucceeded,
        failedDevices: 0,
        skippedAlreadySucceeded,
        skippedInFlight,
      };
    }

    // 6. 트랜잭션 밖에서 FCM 메시지 조립 및 전송 (data-only, title/body 생략)
    const messages: admin.messaging.Message[] = acquiredTargets.map((target) => ({
      token: target.token,
      data: {
        type: "PLACE_ADDED",
        eventId,
        recipientUid: target.recipientUid,
        roomId,
        kakaoPlaceId,
        addedByNickname,
        roomName,
        addedCount: String(addedCount),
      },
      android: {
        priority: "high",
        ttl: 86400000, // 24 hours in milliseconds
      },
    }));

    const BATCH_SIZE = 500;
    let succeededDevices = skippedAlreadySucceeded;
    let failedDevices = 0;
    let hasTransientFailure = false;

    for (let i = 0; i < acquiredTargets.length; i += BATCH_SIZE) {
      const batchTargets = acquiredTargets.slice(i, i + BATCH_SIZE);
      const batchMessages = messages.slice(i, i + BATCH_SIZE);

      const response = await this.messaging.sendEach(batchMessages);

      for (let j = 0; j < response.responses.length; j++) {
        const resp = response.responses[j];
        const target = batchTargets[j];
        const deliveryDocRef = deliveriesRef.doc(target.deliveryId);
        const deviceDocRef = this.db
          .collection("users")
          .doc(target.recipientUid)
          .collection("devices")
          .doc(target.installationId);

        // 7. 단일 결과 트랜잭션 (중첩 트랜잭션 제거 및 순수 트랜잭션 콜백 사용)
        const commitResult = await this.db.runTransaction(async (transaction) => {
          const deliverySnap = await transaction.get(deliveryDocRef);
          const deviceSnap = await transaction.get(deviceDocRef);

          if (!deliverySnap.exists) return "NOT_FOUND";

          const record = deliverySnap.data() as DeliveryRecord;
          if (record.leaseOwner !== executionId) {
            console.warn(`Lease owner changed for delivery ${target.deliveryId}. Skipping status write.`);
            return "STALE_OWNER";
          }

          if (resp.success) {
            transaction.update(deliveryDocRef, {
              status: "success",
              errorCode: null,
              updatedAt: admin.firestore.FieldValue.serverTimestamp(),
            });
            return "SUCCESS";
          } else {
            const errorCode = resp.error?.code || "unknown_error";
            const isUnregistered =
              errorCode === "messaging/registration-token-not-registered" ||
              errorCode === "messaging/invalid-registration-token";

            if (isUnregistered && deviceSnap.exists && deviceSnap.data()?.token === target.token) {
              transaction.delete(deviceDocRef);
            }

            const isPermanent =
              PERMANENT_ERROR_CODES.has(errorCode) || !TRANSIENT_ERROR_CODES.has(errorCode);

            if (isPermanent) {
              transaction.update(deliveryDocRef, {
                status: "failed_permanent",
                errorCode,
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
              });
              return "FAILED_PERMANENT";
            } else {
              // 일시 실패 시 Lease를 즉시 만료(leaseExpiresAt = now)시켜 재시도가 가능하도록 처리
              transaction.update(deliveryDocRef, {
                status: "failed_transient",
                leaseExpiresAt: Date.now(),
                errorCode,
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
              });
              return "FAILED_TRANSIENT";
            }
          }
        });

        // 트랜잭션 커밋 완료 후 외부 상태 집계
        if (commitResult === "SUCCESS") {
          succeededDevices++;
        } else if (commitResult === "FAILED_PERMANENT") {
          failedDevices++;
        } else if (commitResult === "FAILED_TRANSIENT") {
          failedDevices++;
          hasTransientFailure = true;
        } else if (commitResult === "STALE_OWNER") {
          console.log(`Late result for execution ${executionId} ignored due to stale lease owner.`);
        }
      }
    }

    // 8. 상위 notificationEvents 문서 상태를 실시간 자식 Delivery 문서들의 최신 상태에 기반하여 원자적 트랜잭션 업데이트
    const parentSync = await this.syncParentEventStatus(
      eventRef,
      deliveriesRef,
      eventId,
      roomId,
      kakaoPlaceId,
      requestingUid
    );

    if (!parentSync.isAllTerminal || hasTransientFailure || skippedInFlight > 0) {
      throw new Error(`Event ${eventId} has unfulfilled or transient failure targets.`);
    }

    return {
      eventId,
      totalRecipients: recipientUids.length,
      totalDevices: targets.length,
      succeededDevices,
      failedDevices,
      skippedAlreadySucceeded,
      skippedInFlight,
    };
  }

  /**
   * 단일 장소 추가 이벤트 호환성 래퍼 메서드 (Cloud Functions 트리거 및 기존 단일 장소 테스트용)
   */
  async processFriendPlaceAdded(
    roomId: string,
    placeId: string,
    cloudEventId: string,
    placeData: PlaceDocument,
    executionId: string = crypto.randomUUID()
  ): Promise<ProcessNotificationResult> {
    const notificationBatchId = placeData.notificationBatchId || `cf_${placeId}_${cloudEventId}`;
    const overrideEventId = placeData.notificationBatchId
      ? `evt_${roomId}_${placeData.addedBy}_${placeData.notificationBatchId}`
      : `evt_${roomId}_${placeId}_${cloudEventId}`;
    return this.processFriendPlaceAddedBatch({
      roomId,
      notificationBatchId,
      requestingUid: placeData.addedBy,
      executionId,
      fallbackPlaceId: placeId,
      fallbackPlaceData: placeData,
      overrideEventId,
    });
  }
}
