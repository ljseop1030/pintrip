import { expect } from "chai";
import { NotificationService } from "../src/services/notificationService";
import { cleanUpInvalidToken } from "../src/services/tokenCleanupService";
import { PlaceDocument, RoomDocument } from "../src/types";

// In-Memory Fake Firestore Database with Synchronous Transaction operations and Serialized Lock
class FakeDocRef {
  public path: string;
  public dbMap: Map<string, any>;

  constructor(path: string, dbMap: Map<string, any>) {
    this.path = path;
    this.dbMap = dbMap;
  }

  async get() {
    const data = this.dbMap.get(this.path);
    return {
      exists: data !== undefined,
      id: this.path.split("/").pop() || "",
      data: () => (data !== undefined ? { ...data } : undefined),
    };
  }

  setSync(data: any, options?: { merge?: boolean }) {
    if (options?.merge && this.dbMap.has(this.path)) {
      const existing = this.dbMap.get(this.path);
      this.dbMap.set(this.path, { ...existing, ...data });
    } else {
      this.dbMap.set(this.path, { ...data });
    }
  }

  updateSync(data: any) {
    const existing = this.dbMap.get(this.path) || {};
    this.dbMap.set(this.path, { ...existing, ...data });
  }

  deleteSync() {
    this.dbMap.delete(this.path);
  }

  async set(data: any, options?: { merge?: boolean }) {
    this.setSync(data, options);
  }

  async update(data: any) {
    this.updateSync(data);
  }

  async delete() {
    this.deleteSync();
  }

  collection(subPath: string) {
    return new FakeCollectionRef(`${this.path}/${subPath}`, this.dbMap);
  }
}

class FakeCollectionRef {
  public path: string;
  public dbMap: Map<string, any>;

  constructor(path: string, dbMap: Map<string, any>) {
    this.path = path;
    this.dbMap = dbMap;
  }

  doc(id: string) {
    return new FakeDocRef(`${this.path}/${id}`, this.dbMap);
  }

  async get() {
    const docs: any[] = [];
    for (const [key, value] of this.dbMap.entries()) {
      if (
        key.startsWith(this.path + "/") &&
        key.substring(this.path.length + 1).indexOf("/") === -1
      ) {
        docs.push({
          id: key.split("/").pop() || "",
          data: () => (value !== undefined ? { ...value } : undefined),
        });
      }
    }
    return {
      docs,
      empty: docs.length === 0,
    };
  }
}

class FakeFirestore {
  public dbMap = new Map<string, any>();
  private transactionLock = Promise.resolve();

  collection(path: string) {
    return new FakeCollectionRef(path, this.dbMap);
  }

  async runTransaction<T>(updateFunction: (transaction: any) => Promise<T>): Promise<T> {
    const previousLock = this.transactionLock;
    let resolver: () => void;
    this.transactionLock = new Promise<void>((resolve) => {
      resolver = resolve;
    });

    await previousLock;
    try {
      const transaction = {
        get: async (ref: FakeDocRef) => ref.get(),
        set: (ref: FakeDocRef, data: any, options?: any) => {
          ref.setSync(data, options);
        },
        update: (ref: FakeDocRef, data: any) => {
          ref.updateSync(data);
        },
        delete: (ref: FakeDocRef) => {
          ref.deleteSync();
        },
      };
      return await updateFunction(transaction);
    } finally {
      resolver!();
    }
  }
}

// Fake Messaging for testing
class FakeMessaging {
  public sentMessages: any[] = [];
  public failTokens = new Map<string, string>(); // token -> errorCode

  async sendEach(messages: any[]) {
    const responses: any[] = [];
    for (const msg of messages) {
      this.sentMessages.push(msg);
      const failCode = this.failTokens.get(msg.token);
      if (failCode) {
        responses.push({
          success: false,
          error: { code: failCode, message: "Fake failure" },
        });
      } else {
        responses.push({
          success: true,
          messageId: `msg_${Math.random()}`,
        });
      }
    }
    return {
      responses,
      successCount: responses.filter((r) => r.success).length,
      failureCount: responses.filter((r) => !r.success).length,
    };
  }
}

describe("S02 Friend Place Added FCM Notification Tests", () => {
  let fakeDb: FakeFirestore;
  let fakeMessaging: FakeMessaging;
  let service: NotificationService;

  beforeEach(() => {
    fakeDb = new FakeFirestore();
    fakeMessaging = new FakeMessaging();
    service = new NotificationService({
      db: fakeDb as any,
      messaging: fakeMessaging as any,
    });
  });

  it("filters out place author, omits title/body from data payload, and sends distinct recipientUid to each device", async () => {
    const roomData: RoomDocument = {
      name: "Jeju Trip",
      region: "제주",
      ownerId: "user_author",
      memberIds: ["user_author", "user_member1", "user_member2"],
      members: {
        user_author: { nickname: "Minji", avatarColor: "#FF0000" },
        user_member1: { nickname: "Hani", avatarColor: "#00FF00" },
        user_member2: { nickname: "Danielle", avatarColor: "#0000FF" },
      },
      placeCount: 1,
      createdAt: null,
      updatedAt: null,
    };
    fakeDb.dbMap.set("rooms/room_100", roomData);

    fakeDb.dbMap.set("users/user_author/devices/dev_auth", { token: "tok_author", platform: "android" });
    fakeDb.dbMap.set("users/user_member1/devices/dev_m1", { token: "tok_member1", platform: "android" });
    fakeDb.dbMap.set("users/user_member2/devices/dev_m2", { token: "tok_member2", platform: "android" });

    const placeData: PlaceDocument = {
      kakaoPlaceId: "place_789",
      kakaoPlaceUrl: "https://place.map.kakao.com/789",
      searchKeyword: "해운대 해수욕장",
      matchType: "confirmed",
      sourceType: "text",
      addedBy: "user_author",
      addedByNickname: "Minji",
      likedBy: [],
      likeCount: 0,
      createdAt: null,
    };

    const result = await service.processFriendPlaceAdded("room_100", "place_789", "evt_cloud_1", placeData);

    expect(result.totalRecipients).to.equal(2);
    expect(result.succeededDevices).to.equal(2);
    expect(fakeMessaging.sentMessages.length).to.equal(2);

    const sentTokens = fakeMessaging.sentMessages.map((m) => m.token);
    expect(sentTokens).to.not.include("tok_author");
    expect(sentTokens).to.include("tok_member1");
    expect(sentTokens).to.include("tok_member2");

    for (const msg of fakeMessaging.sentMessages) {
      expect(msg.notification).to.be.undefined;
      expect(msg.android.priority).to.equal("high");
      expect(msg.android.ttl).to.equal(86400000);

      expect(msg.data.type).to.equal("PLACE_ADDED");
      expect(msg.data.roomId).to.equal("room_100");
      expect(msg.data.kakaoPlaceId).to.equal("place_789");
      expect(msg.data.recipientUid).to.be.a("string");
      expect(msg.data.title).to.be.undefined;
      expect(msg.data.body).to.be.undefined;
    }

    const msg1 = fakeMessaging.sentMessages.find((m) => m.token === "tok_member1");
    expect(msg1.data.recipientUid).to.equal("user_member1");

    const msg2 = fakeMessaging.sentMessages.find((m) => m.token === "tok_member2");
    expect(msg2.data.recipientUid).to.equal("user_member2");
  });

  it("prevents duplicate send on concurrent executions and throws error for retry when target is in-flight", async () => {
    const roomData: RoomDocument = {
      name: "Busan Trip",
      region: "부산",
      ownerId: "user_author",
      memberIds: ["user_author", "user_m1"],
      members: {
        user_author: { nickname: "Minji", avatarColor: "#FF0000" },
        user_m1: { nickname: "Hani", avatarColor: "#00FF00" },
      },
      placeCount: 1,
      createdAt: null,
      updatedAt: null,
    };
    fakeDb.dbMap.set("rooms/room_concurrent", roomData);
    fakeDb.dbMap.set("users/user_m1/devices/dev_m1", { token: "tok_m1", platform: "android" });

    const placeData: PlaceDocument = {
      kakaoPlaceId: "place_c",
      kakaoPlaceUrl: "https://place.map.kakao.com/c",
      searchKeyword: "태종대",
      matchType: "confirmed",
      sourceType: "text",
      addedBy: "user_author",
      addedByNickname: "Minji",
      likedBy: [],
      likeCount: 0,
      createdAt: null,
    };

    const promise1 = service.processFriendPlaceAdded("room_concurrent", "place_c", "evt_conc", placeData, "exec_1");
    const promise2 = service.processFriendPlaceAdded("room_concurrent", "place_c", "evt_conc", placeData, "exec_2");

    let exec1Result: any;
    let exec2Threw = false;

    try {
      exec1Result = await promise1;
    } catch (_e) {
      exec1Result = null;
    }

    try {
      await promise2;
    } catch (e: any) {
      exec2Threw = true;
      expect(e.message).to.include("unfulfilled");
    }

    expect(fakeMessaging.sentMessages.length).to.equal(1);
    expect(exec2Threw || exec1Result !== null).to.be.true;
  });

  it("Reproduction A Fix: stale execution late completion does not overwrite parent status to completed", async () => {
    const roomData: RoomDocument = {
      name: "Repro A Room",
      region: "서울",
      ownerId: "user_author",
      memberIds: ["user_author", "user_m1"],
      members: {
        user_author: { nickname: "Minji", avatarColor: "#FF0000" },
        user_m1: { nickname: "Hani", avatarColor: "#00FF00" },
      },
      placeCount: 1,
      createdAt: null,
      updatedAt: null,
    };
    fakeDb.dbMap.set("rooms/room_repro_a", roomData);
    fakeDb.dbMap.set("users/user_m1/devices/dev_m1", { token: "tok_transient", platform: "android" });

    const placeData: PlaceDocument = {
      kakaoPlaceId: "place_repro_a",
      kakaoPlaceUrl: "url",
      searchKeyword: "N타워",
      matchType: "confirmed",
      sourceType: "text",
      addedBy: "user_author",
      addedByNickname: "Minji",
      likedBy: [],
      likeCount: 0,
      createdAt: null,
    };

    const eventId = "evt_room_repro_a_place_repro_a_cloud_a";

    // Simulate Execution B already ran, failed transiently on tok_transient, and set delivery & parent status = failed_transient
    fakeDb.dbMap.set(`notificationEvents/${eventId}`, {
      eventId,
      roomId: "room_repro_a",
      kakaoPlaceId: "place_repro_a",
      addedBy: "user_author",
      status: "failed_transient",
      createdAt: 1000,
      updatedAt: 1000,
    });

    fakeDb.dbMap.set(`notificationEvents/${eventId}/deliveries/user_m1_dev_m1`, {
      deliveryId: "user_m1_dev_m1",
      recipientUid: "user_m1",
      installationId: "dev_m1",
      status: "failed_transient",
      leaseOwner: "exec_b",
      leaseExpiresAt: Date.now() + 300000,
      retryCount: 1,
      updatedAt: 1000,
    });

    // Stale Execution A (exec_a) finishes late and calls processFriendPlaceAdded
    let execAThrew = false;
    try {
      await service.processFriendPlaceAdded("room_repro_a", "place_repro_a", "cloud_a", placeData, "exec_a");
    } catch (_e) {
      execAThrew = true;
    }

    // Parent event doc status MUST REMAIN "failed_transient" and MUST NOT be overwritten to "completed"!
    const parentDoc = fakeDb.dbMap.get(`notificationEvents/${eventId}`);
    const deliveryDoc = fakeDb.dbMap.get(`notificationEvents/${eventId}/deliveries/user_m1_dev_m1`);

    expect(parentDoc.status).to.equal("failed_transient");
    expect(deliveryDoc.status).to.equal("failed_transient");
    expect(execAThrew).to.be.true;
  });

  it("Reproduction B Fix: mixed success and in-flight lease targets keep parent status as in_progress and throw error", async () => {
    const roomData: RoomDocument = {
      name: "Repro B Room",
      region: "부산",
      ownerId: "user_author",
      memberIds: ["user_author", "user_m1", "user_m2"],
      members: {
        user_author: { nickname: "Minji", avatarColor: "#FF0000" },
        user_m1: { nickname: "Hani", avatarColor: "#00FF00" },
        user_m2: { nickname: "Danielle", avatarColor: "#0000FF" },
      },
      placeCount: 1,
      createdAt: null,
      updatedAt: null,
    };
    fakeDb.dbMap.set("rooms/room_repro_b", roomData);
    fakeDb.dbMap.set("users/user_m1/devices/dev_m1", { token: "tok_m1", platform: "android" });
    fakeDb.dbMap.set("users/user_m2/devices/dev_m2", { token: "tok_m2", platform: "android" });

    const placeData: PlaceDocument = {
      kakaoPlaceId: "place_repro_b",
      kakaoPlaceUrl: "url",
      searchKeyword: "해운대",
      matchType: "confirmed",
      sourceType: "text",
      addedBy: "user_author",
      addedByNickname: "Minji",
      likedBy: [],
      likeCount: 0,
      createdAt: null,
    };

    const eventId = "evt_room_repro_b_place_repro_b_cloud_b";

    // Seed dev_m1 as IN_FLIGHT owned by exec_active
    fakeDb.dbMap.set(`notificationEvents/${eventId}/deliveries/user_m1_dev_m1`, {
      deliveryId: "user_m1_dev_m1",
      recipientUid: "user_m1",
      installationId: "dev_m1",
      status: "in_progress",
      leaseOwner: "exec_active",
      leaseExpiresAt: Date.now() + 300000,
      retryCount: 0,
      updatedAt: Date.now(),
    });

    // Execution exec_2 runs: dev_m1 is in_progress (skippedInFlight), dev_m2 is succeeded
    let threwError = false;
    try {
      await service.processFriendPlaceAdded("room_repro_b", "place_repro_b", "cloud_b", placeData, "exec_2");
    } catch (e: any) {
      threwError = true;
      expect(e.message).to.include("unfulfilled or transient failure");
    }

    expect(threwError).to.be.true;

    // Parent doc status MUST NOT be "completed"!
    const parentDoc = fakeDb.dbMap.get(`notificationEvents/${eventId}`);
    expect(parentDoc.status).to.not.equal("completed");
    expect(parentDoc.status).to.equal("in_progress");
  });

  it("handles separate creation events for deleted/re-added place with distinct eventId", async () => {
    const roomData: RoomDocument = {
      name: "Jeju Trip",
      region: "제주",
      ownerId: "user_author",
      memberIds: ["user_author", "user_m1"],
      members: {
        user_author: { nickname: "Minji", avatarColor: "#FF0000" },
        user_m1: { nickname: "Hani", avatarColor: "#00FF00" },
      },
      placeCount: 1,
      createdAt: null,
      updatedAt: null,
    };
    fakeDb.dbMap.set("rooms/room_readd", roomData);
    fakeDb.dbMap.set("users/user_m1/devices/dev_m1", { token: "tok_m1", platform: "android" });

    const placeData: PlaceDocument = {
      kakaoPlaceId: "place_readd",
      kakaoPlaceUrl: "url",
      searchKeyword: "성산일출봉",
      matchType: "confirmed",
      sourceType: "text",
      addedBy: "user_author",
      addedByNickname: "Minji",
      likedBy: [],
      likeCount: 0,
      createdAt: null,
    };

    // 1st Creation Event
    const res1 = await service.processFriendPlaceAdded("room_readd", "place_readd", "evt_cloud_event_1", placeData);
    expect(res1.succeededDevices).to.equal(1);
    expect(fakeMessaging.sentMessages.length).to.equal(1);

    // Place deleted and re-added -> 2nd Creation Event (different cloudEventId -> distinct eventId!)
    fakeMessaging.sentMessages = [];
    const res2 = await service.processFriendPlaceAdded("room_readd", "place_readd", "evt_cloud_event_2", placeData);
    expect(res2.succeededDevices).to.equal(1);
    expect(fakeMessaging.sentMessages.length).to.equal(1);
    expect(res2.eventId).to.not.equal(res1.eventId);
  });

  it("recovers expired in-flight lease and prevents stale lease owner late result from overwriting success", async () => {
    const roomData: RoomDocument = {
      name: "Jeju",
      region: "제주",
      ownerId: "user_author",
      memberIds: ["user_author", "user_m1"],
      members: {
        user_author: { nickname: "Minji", avatarColor: "#FF0000" },
        user_m1: { nickname: "Hani", avatarColor: "#00FF00" },
      },
      placeCount: 1,
      createdAt: null,
      updatedAt: null,
    };
    fakeDb.dbMap.set("rooms/room_expired", roomData);
    fakeDb.dbMap.set("users/user_m1/devices/dev_m1", { token: "tok_m1", platform: "android" });

    const placeData: PlaceDocument = {
      kakaoPlaceId: "place_exp",
      kakaoPlaceUrl: "url",
      searchKeyword: "한라산",
      matchType: "confirmed",
      sourceType: "text",
      addedBy: "user_author",
      addedByNickname: "Minji",
      likedBy: [],
      likeCount: 0,
      createdAt: null,
    };

    // Seed an expired lease from crashed exec_old
    const eventId = "evt_room_expired_place_exp_evt_crashed";
    fakeDb.dbMap.set(`notificationEvents/${eventId}/deliveries/user_m1_dev_m1`, {
      deliveryId: "user_m1_dev_m1",
      recipientUid: "user_m1",
      installationId: "dev_m1",
      status: "in_progress",
      leaseOwner: "exec_old",
      leaseExpiresAt: Date.now() - 10000, // Expired 10s ago
      retryCount: 1,
      updatedAt: null,
    });

    // New execution acquires expired lease and sends message
    const res = await service.processFriendPlaceAdded("room_expired", "place_exp", "evt_crashed", placeData, "exec_new");
    expect(res.succeededDevices).to.equal(1);
    expect(fakeMessaging.sentMessages.length).to.equal(1);

    const deliveryDoc = fakeDb.dbMap.get(`notificationEvents/${eventId}/deliveries/user_m1_dev_m1`);
    expect(deliveryDoc.status).to.equal("success");
  });

  it("classifies permanent vs transient errors, skips failed_permanent on retry, and cleans up token in transaction", async () => {
    const roomData: RoomDocument = {
      name: "Gangneung",
      region: "강릉",
      ownerId: "user_author",
      memberIds: ["user_author", "user_m1"],
      members: {
        user_author: { nickname: "Minji", avatarColor: "#FF0000" },
        user_m1: { nickname: "Hani", avatarColor: "#00FF00" },
      },
      placeCount: 1,
      createdAt: null,
      updatedAt: null,
    };
    fakeDb.dbMap.set("rooms/room_perm", roomData);
    fakeDb.dbMap.set("users/user_m1/devices/dev_m1", { token: "tok_invalid_payload", platform: "android" });

    // messaging/invalid-payload is permanent error
    fakeMessaging.failTokens.set("tok_invalid_payload", "messaging/invalid-payload");

    const placeData: PlaceDocument = {
      kakaoPlaceId: "place_p",
      kakaoPlaceUrl: "url",
      searchKeyword: "경포대",
      matchType: "confirmed",
      sourceType: "text",
      addedBy: "user_author",
      addedByNickname: "Minji",
      likedBy: [],
      likeCount: 0,
      createdAt: null,
    };

    // Execution completes without throwing transient error because invalid-payload is permanent
    const res = await service.processFriendPlaceAdded("room_perm", "place_p", "evt_perm", placeData, "exec_1");
    expect(res.failedDevices).to.equal(1);

    const eventId = "evt_room_perm_place_p_evt_perm";
    const deliveryDoc = fakeDb.dbMap.get(`notificationEvents/${eventId}/deliveries/user_m1_dev_m1`);
    expect(deliveryDoc.status).to.equal("failed_permanent");

    // Retry processFriendPlaceAdded -> skips failed_permanent target
    fakeMessaging.sentMessages = [];
    await service.processFriendPlaceAdded("room_perm", "place_p", "evt_perm", placeData, "exec_2");
    expect(fakeMessaging.sentMessages.length).to.equal(0);
  });

  it("cleans up invalid token in transaction ONLY IF current document token matches failed token", async () => {
    fakeDb.dbMap.set("users/user_stale/devices/dev_stale", {
      token: "tok_stale_123",
      platform: "android",
    });

    // Token matches -> deletes document in transaction
    const res1 = await cleanUpInvalidToken(fakeDb as any, "user_stale", "dev_stale", "tok_stale_123");
    expect(res1).to.be.true;
    expect(fakeDb.dbMap.has("users/user_stale/devices/dev_stale")).to.be.false;

    // Device updated token in the meantime
    fakeDb.dbMap.set("users/user_stale/devices/dev_stale", {
      token: "tok_NEW_456",
      platform: "android",
    });

    // Cleanup with old failed token -> transaction sees token mismatch and ABORTS deletion
    const res2 = await cleanUpInvalidToken(fakeDb as any, "user_stale", "dev_stale", "tok_stale_123");
    expect(res2).to.be.false;
    expect(fakeDb.dbMap.has("users/user_stale/devices/dev_stale")).to.be.true;
    expect(fakeDb.dbMap.get("users/user_stale/devices/dev_stale").token).to.equal("tok_NEW_456");
  });

  it("aborts when parent room is missing or author is not a room member or path placeId mismatches", async () => {
    const placeData: PlaceDocument = {
      kakaoPlaceId: "place_1",
      kakaoPlaceUrl: "url",
      searchKeyword: "keyword",
      matchType: "auto",
      sourceType: "text",
      addedBy: "user_stranger",
      addedByNickname: "Stranger",
      likedBy: [],
      likeCount: 0,
      createdAt: null,
    };

    // Case 1: Room missing
    const res1 = await service.processFriendPlaceAdded("room_missing", "place_1", "evt_1", placeData);
    expect(res1.totalDevices).to.equal(0);
    expect(fakeMessaging.sentMessages.length).to.equal(0);

    // Case 2: Room exists, but addedBy is not in memberIds
    fakeDb.dbMap.set("rooms/room_300", {
      memberIds: ["user_legit1", "user_legit2"],
    });

    const res2 = await service.processFriendPlaceAdded("room_300", "place_1", "evt_1", placeData);
    expect(res2.totalDevices).to.equal(0);
    expect(fakeMessaging.sentMessages.length).to.equal(0);

    // Case 3: Path placeId mismatches kakaoPlaceId
    const res3 = await service.processFriendPlaceAdded("room_300", "place_MISMATCH", "evt_1", placeData);
    expect(res3.totalDevices).to.equal(0);
    expect(fakeMessaging.sentMessages.length).to.equal(0);
  });
});
