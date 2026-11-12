const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require("@firebase/rules-unit-testing");
const { serverTimestamp } = require("firebase/firestore");
const fs = require("fs");
const path = require("path");

const PROJECT_ID = "demo-pintrip";
const RULES_PATH = path.resolve(__dirname, "../../firestore.rules");

describe("Firestore Security Rules Tests", () => {
  let testEnv;

  before(async () => {
    const rules = fs.readFileSync(RULES_PATH, "utf8");
    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: {
        rules: rules,
        host: "127.0.0.1",
        port: 8080,
      },
    });
  });

  after(async () => {
    if (testEnv) {
      await testEnv.cleanup();
    }
  });

  beforeEach(async () => {
    if (testEnv) {
      await testEnv.clearFirestore();
    }
  });

  function getDb(auth) {
    return auth
      ? testEnv.authenticatedContext(auth.uid, auth.token || {}).firestore()
      : testEnv.unauthenticatedContext().firestore();
  }

  async function seedUserProfile(uid, nickname, email, avatarColor) {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await db.collection("users").doc(uid).set({
        nickname: nickname,
        email: email,
        avatarColor: avatarColor,
        createdAt: new Date(),
      });
    });
  }

  async function seedRoom(roomId, roomData) {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await db.collection("rooms").doc(roomId).set(roomData);
    });
  }

  async function seedInviteCode(code, inviteCodeData) {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await db.collection("inviteCodes").doc(code).set(inviteCodeData);
    });
  }

  // -------------------------------------------------------------------
  // 1. Atomic Room & InviteCode Creation
  // -------------------------------------------------------------------
  describe("Atomic Room & Invite Code Creation", () => {
    it("allows atomic creation of room and invite code when fields, profile match, and server timestamp are valid", async () => {
      const uid = "user_a";
      await seedUserProfile(uid, "민지", "minji@test.com", "#FF5722");

      const db = getDb({ uid, token: { email: "minji@test.com" } });
      const batch = db.batch();

      const roomId = "room_new_1";
      const code = "K7P2QX";

      const roomRef = db.collection("rooms").doc(roomId);
      const inviteCodeRef = db.collection("inviteCodes").doc(code);

      batch.set(roomRef, {
        name: "부산 여행",
        region: "부산",
        startDate: null,
        endDate: null,
        icon: "sea",
        inviteCode: code,
        ownerId: uid,
        memberIds: [uid],
        members: {
          user_a: {
            nickname: "민지",
            avatarColor: "#FF5722",
            lastSeenAt: null,
          },
        },
        placeCount: 0,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });

      batch.set(inviteCodeRef, {
        roomId: roomId,
        createdAt: serverTimestamp(),
      });

      await assertSucceeds(batch.commit());
    });

    it("rejects creation if inviteCodes document is not created in the same batch", async () => {
      const uid = "user_a";
      await seedUserProfile(uid, "민지", "minji@test.com", "#FF5722");

      const db = getDb({ uid, token: { email: "minji@test.com" } });
      const roomRef = db.collection("rooms").doc("room_single");

      await assertFails(
        roomRef.set({
          name: "부산 여행",
          region: "부산",
          startDate: null,
          endDate: null,
          icon: "sea",
          inviteCode: "K7P2QX",
          ownerId: uid,
          memberIds: [uid],
          members: {
            user_a: {
              nickname: "민지",
              avatarColor: "#FF5722",
              lastSeenAt: null,
            },
          },
          placeCount: 0,
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        })
      );
    });

    it("rejects creation if inviteCode document already existed (!exists check)", async () => {
      const uid = "user_a";
      await seedUserProfile(uid, "민지", "minji@test.com", "#FF5722");
      await seedInviteCode("K7P2QX", { roomId: "existing_room", createdAt: new Date() });

      const db = getDb({ uid });
      const batch = db.batch();

      batch.set(db.collection("rooms").doc("room_dup"), {
        name: "부산 여행",
        region: "부산",
        startDate: null,
        endDate: null,
        icon: "sea",
        inviteCode: "K7P2QX",
        ownerId: uid,
        memberIds: [uid],
        members: {
          user_a: { nickname: "민지", avatarColor: "#FF5722", lastSeenAt: null },
        },
        placeCount: 0,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });

      batch.set(db.collection("inviteCodes").doc("K7P2QX"), {
        roomId: "room_dup",
        createdAt: serverTimestamp(),
      });

      await assertFails(batch.commit());
    });

    it("rejects creation if room members nickname/avatarColor does not match users profile", async () => {
      const uid = "user_a";
      await seedUserProfile(uid, "민지", "minji@test.com", "#FF5722");

      const db = getDb({ uid });
      const batch = db.batch();

      batch.set(db.collection("rooms").doc("room_mismatch"), {
        name: "부산 여행",
        region: "부산",
        startDate: null,
        endDate: null,
        icon: "sea",
        inviteCode: "K7P2QX",
        ownerId: uid,
        memberIds: [uid],
        members: {
          user_a: { nickname: "위조닉네임", avatarColor: "#FF5722", lastSeenAt: null },
        },
        placeCount: 0,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });

      batch.set(db.collection("inviteCodes").doc("K7P2QX"), {
        roomId: "room_mismatch",
        createdAt: serverTimestamp(),
      });

      await assertFails(batch.commit());
    });

    it("rejects creation if lastSeenAt is set to a timestamp instead of null", async () => {
      const uid = "user_a";
      await seedUserProfile(uid, "민지", "minji@test.com", "#FF5722");

      const db = getDb({ uid });
      const batch = db.batch();

      batch.set(db.collection("rooms").doc("room_lastseen"), {
        name: "부산 여행",
        region: "부산",
        startDate: null,
        endDate: null,
        icon: "sea",
        inviteCode: "K7P2QX",
        ownerId: uid,
        memberIds: [uid],
        members: {
          user_a: { nickname: "민지", avatarColor: "#FF5722", lastSeenAt: serverTimestamp() },
        },
        placeCount: 0,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });

      batch.set(db.collection("inviteCodes").doc("K7P2QX"), {
        roomId: "room_lastseen",
        createdAt: serverTimestamp(),
      });

      await assertFails(batch.commit());
    });
  });

  // -------------------------------------------------------------------
  // 2. Read & Query Permissions (rooms & inviteCodes)
  // -------------------------------------------------------------------
  describe("Read & Query Permissions", () => {
    it("allows members to read room and list my rooms", async () => {
      const uid = "user_a";
      await seedRoom("room_1", {
        name: "부산 여행",
        memberIds: [uid],
        ownerId: uid,
      });

      const db = getDb({ uid });
      await assertSucceeds(db.collection("rooms").doc("room_1").get());

      const query = db.collection("rooms").where("memberIds", "array-contains", uid);
      await assertSucceeds(query.get());
    });

    it("rejects non-members from reading room or querying my rooms", async () => {
      await seedRoom("room_1", {
        name: "부산 여행",
        memberIds: ["owner_uid"],
        ownerId: "owner_uid",
      });

      const db = getDb({ uid: "other_user" });
      await assertFails(db.collection("rooms").doc("room_1").get());
    });

    it("rejects unauthenticated read access to room", async () => {
      await seedRoom("room_1", { name: "부산 여행", memberIds: ["owner_uid"] });
      const db = getDb(null);
      await assertFails(db.collection("rooms").doc("room_1").get());
    });

    it("allows signed-in user to get single candidate inviteCode (collision check)", async () => {
      await seedInviteCode("K7P2QX", { roomId: "room_1", createdAt: new Date() });

      const db = getDb({ uid: "user_a" });
      await assertSucceeds(db.collection("inviteCodes").doc("K7P2QX").get());
      await assertSucceeds(db.collection("inviteCodes").doc("NONEXP").get());
    });

    it("rejects inviteCodes list (querying entire collection)", async () => {
      const db = getDb({ uid: "user_a" });
      await assertFails(db.collection("inviteCodes").get());
    });

    it("rejects non-member self-join update in current phase", async () => {
      await seedRoom("room_1", {
        name: "부산 여행",
        memberIds: ["owner_uid"],
        ownerId: "owner_uid",
      });

      const db = getDb({ uid: "new_joiner" });
      await assertFails(
        db.collection("rooms").doc("room_1").update({
          memberIds: ["owner_uid", "new_joiner"],
        })
      );
    });

    it("rejects room deletion", async () => {
      await seedRoom("room_1", { name: "부산 여행", memberIds: ["user_a"], ownerId: "user_a" });
      const db = getDb({ uid: "user_a" });
      await assertFails(db.collection("rooms").doc("room_1").delete());
    });
  });

  // -------------------------------------------------------------------
  // 3. Places Subcollection Permissions
  // -------------------------------------------------------------------
  describe("Places Subcollection Permissions", () => {
    it("allows room member to read places and create place with parent placeCount increment", async () => {
      const uid = "user_a";
      const now = new Date();
      await seedRoom("room_1", {
        name: "부산 여행",
        region: "부산",
        icon: "sea",
        inviteCode: "K7P2QX",
        createdAt: now,
        updatedAt: now,
        memberIds: [uid],
        ownerId: uid,
        members: {
          user_a: { nickname: "민지", avatarColor: "#FF5722", lastSeenAt: null },
        },
        placeCount: 0,
      });

      const db = getDb({ uid });

      await assertSucceeds(db.collection("rooms").doc("room_1").collection("places").doc("p123").get());

      const batch = db.batch();
      const placeRef = db.collection("rooms").doc("room_1").collection("places").doc("p123");
      const roomRef = db.collection("rooms").doc("room_1");

      batch.set(placeRef, {
        kakaoPlaceId: "p123",
        kakaoPlaceUrl: "https://place.map.kakao.com/p123",
        searchKeyword: "모모스커피",
        searchRegion: null,
        matchType: "manual",
        sourceType: "link",
        sourceUrl: null,
        addedBy: uid,
        addedByNickname: "민지",
        likedBy: [],
        likeCount: 0,
        createdAt: serverTimestamp(),
      });

      batch.update(roomRef, {
        placeCount: 1,
      });

      await assertSucceeds(batch.commit());
    });

    it("rejects place creation if addedBy does not match authenticated uid", async () => {
      const uid = "user_a";
      const now = new Date();
      await seedRoom("room_1", {
        name: "부산 여행",
        region: "부산",
        icon: "sea",
        inviteCode: "K7P2QX",
        createdAt: now,
        updatedAt: now,
        memberIds: [uid],
        ownerId: uid,
        members: {
          user_a: { nickname: "민지", avatarColor: "#FF5722", lastSeenAt: null },
        },
        placeCount: 0,
      });

      const db = getDb({ uid });
      const batch = db.batch();

      batch.set(db.collection("rooms").doc("room_1").collection("places").doc("p123"), {
        kakaoPlaceId: "p123",
        kakaoPlaceUrl: "https://place.map.kakao.com/p123",
        searchKeyword: "모모스커피",
        matchType: "manual",
        sourceType: "link",
        addedBy: "other_uid",
        addedByNickname: "민지",
        likedBy: [],
        likeCount: 0,
        createdAt: serverTimestamp(),
      });

      batch.update(db.collection("rooms").doc("room_1"), { placeCount: 1 });

      await assertFails(batch.commit());
    });

    it("rejects non-member from reading or creating places", async () => {
      await seedRoom("room_1", { name: "부산 여행", memberIds: ["owner_uid"], placeCount: 0 });

      const db = getDb({ uid: "stranger" });
      await assertFails(db.collection("rooms").doc("room_1").collection("places").get());
    });

    it("rejects place update and delete", async () => {
      const uid = "user_a";
      await seedRoom("room_1", { name: "부산 여행", memberIds: [uid], placeCount: 1 });

      await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore().collection("rooms").doc("room_1").collection("places").doc("p123").set({
          kakaoPlaceId: "p123",
          addedBy: uid,
        });
      });

      const db = getDb({ uid });
      const placeRef = db.collection("rooms").doc("room_1").collection("places").doc("p123");

      await assertFails(placeRef.update({ likeCount: 1 }));
      await assertFails(placeRef.delete());
    });
  });
});
