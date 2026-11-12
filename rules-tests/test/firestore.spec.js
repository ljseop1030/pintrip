const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require("@firebase/rules-unit-testing");
const { serverTimestamp } = require("firebase/firestore");
const assert = require("assert");
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
    it("allows atomic creation of room and invite code, and verifies lastSeenAt is null on read", async () => {
      const uid = "user_a";
      await seedUserProfile(uid, "민지", "minji@test.com", "#FF5722");

      const db = getDb({ uid, token: { email: "minji@test.com" } });
      const batch = db.batch();

      const roomId = "room_new_1";
      const code = "K7P2QX";

      const roomRef = db.collection("rooms").doc(roomId);
      const inviteCodeRef = db.collection("inviteCodes").doc(code);

      const serverTime = serverTimestamp();

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
        createdAt: serverTime,
        updatedAt: serverTime,
      });

      batch.set(inviteCodeRef, {
        roomId: roomId,
        createdAt: serverTime,
      });

      await assertSucceeds(batch.commit());

      // Read back document using member context to assert field values
      const roomSnap = await db.collection("rooms").doc(roomId).get();
      assert.strictEqual(roomSnap.exists, true);
      assert.strictEqual(roomSnap.data().name, "부산 여행");
      assert.strictEqual(roomSnap.data().members.user_a.lastSeenAt, null);

      const inviteSnap = await db.collection("inviteCodes").doc(code).get();
      assert.strictEqual(inviteSnap.exists, true);
      assert.strictEqual(inviteSnap.data().roomId, roomId);
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
            user_a: { nickname: "민지", avatarColor: "#FF5722", lastSeenAt: null },
          },
          placeCount: 0,
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        })
      );
    });

    it("rejects creation if inviteCode alone is created without a room", async () => {
      const uid = "user_a";
      const db = getDb({ uid });
      const inviteCodeRef = db.collection("inviteCodes").doc("K7P2QX");

      await assertFails(
        inviteCodeRef.set({
          roomId: "room_non_existent",
          createdAt: serverTimestamp(),
        })
      );
    });

    it("rejects creation if room and inviteCode connection mismatch", async () => {
      const uid = "user_a";
      await seedUserProfile(uid, "민지", "minji@test.com", "#FF5722");

      const db = getDb({ uid });
      const batch = db.batch();

      batch.set(db.collection("rooms").doc("room_a"), {
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

      // Mismatched roomId in inviteCode
      batch.set(db.collection("inviteCodes").doc("K7P2QX"), {
        roomId: "room_other",
        createdAt: serverTimestamp(),
      });

      await assertFails(batch.commit());
    });

    it("rejects creation if ownerId is forged to another uid", async () => {
      const uid = "user_a";
      await seedUserProfile(uid, "민지", "minji@test.com", "#FF5722");

      const db = getDb({ uid });
      const batch = db.batch();

      batch.set(db.collection("rooms").doc("room_fake_owner"), {
        name: "부산 여행",
        region: "부산",
        startDate: null,
        endDate: null,
        icon: "sea",
        inviteCode: "K7P2QX",
        ownerId: "other_uid", // Forged owner
        memberIds: [uid],
        members: {
          user_a: { nickname: "민지", avatarColor: "#FF5722", lastSeenAt: null },
        },
        placeCount: 0,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });

      batch.set(db.collection("inviteCodes").doc("K7P2QX"), {
        roomId: "room_fake_owner",
        createdAt: serverTimestamp(),
      });

      await assertFails(batch.commit());
    });

    it("rejects creation if extra initial members are included", async () => {
      const uid = "user_a";
      await seedUserProfile(uid, "민지", "minji@test.com", "#FF5722");

      const db = getDb({ uid });
      const batch = db.batch();

      batch.set(db.collection("rooms").doc("room_extra_members"), {
        name: "부산 여행",
        region: "부산",
        startDate: null,
        endDate: null,
        icon: "sea",
        inviteCode: "K7P2QX",
        ownerId: uid,
        memberIds: [uid, "user_b"], // Extra member
        members: {
          user_a: { nickname: "민지", avatarColor: "#FF5722", lastSeenAt: null },
          user_b: { nickname: "철수", avatarColor: "#000000", lastSeenAt: null },
        },
        placeCount: 0,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });

      batch.set(db.collection("inviteCodes").doc("K7P2QX"), {
        roomId: "room_extra_members",
        createdAt: serverTimestamp(),
      });

      await assertFails(batch.commit());
    });

    it("rejects creation if nickname in members does not match users profile", async () => {
      const uid = "user_a";
      await seedUserProfile(uid, "민지", "minji@test.com", "#FF5722");

      const db = getDb({ uid });
      const batch = db.batch();

      batch.set(db.collection("rooms").doc("room_nick_mismatch"), {
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
        roomId: "room_nick_mismatch",
        createdAt: serverTimestamp(),
      });

      await assertFails(batch.commit());
    });

    it("rejects creation if avatarColor in members does not match users profile", async () => {
      const uid = "user_a";
      await seedUserProfile(uid, "민지", "minji@test.com", "#FF5722");

      const db = getDb({ uid });
      const batch = db.batch();

      batch.set(db.collection("rooms").doc("room_color_mismatch"), {
        name: "부산 여행",
        region: "부산",
        startDate: null,
        endDate: null,
        icon: "sea",
        inviteCode: "K7P2QX",
        ownerId: uid,
        memberIds: [uid],
        members: {
          user_a: { nickname: "민지", avatarColor: "#112233", lastSeenAt: null }, // Forged color
        },
        placeCount: 0,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });

      batch.set(db.collection("inviteCodes").doc("K7P2QX"), {
        roomId: "room_color_mismatch",
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
    it("allows members to query my rooms filtered by memberIds and ordered by updatedAt", async () => {
      const uid = "user_a";
      await seedRoom("room_1", {
        name: "부산 여행",
        memberIds: [uid],
        ownerId: uid,
        updatedAt: new Date(),
      });

      const db = getDb({ uid });
      const query = db.collection("rooms").where("memberIds", "array-contains", uid).orderBy("updatedAt", "desc");
      const snap = await assertSucceeds(query.get());
      assert.strictEqual(snap.docs.length, 1);
      assert.strictEqual(snap.docs[0].id, "room_1");
    });

    it("returns empty list when user has no participating rooms", async () => {
      const uid = "user_empty";
      const db = getDb({ uid });
      const query = db.collection("rooms").where("memberIds", "array-contains", uid);
      const snap = await assertSucceeds(query.get());
      assert.strictEqual(snap.docs.length, 0);
    });

    it("rejects querying other users rooms or querying all rooms without memberIds filter", async () => {
      const db = getDb({ uid: "user_a" });

      // Query other user's rooms
      const otherQuery = db.collection("rooms").where("memberIds", "array-contains", "other_uid");
      await assertFails(otherQuery.get());

      // Query all rooms
      const allQuery = db.collection("rooms");
      await assertFails(allQuery.get());
    });

    it("rejects non-members from reading another users room", async () => {
      await seedRoom("room_1", {
        name: "부산 여행",
        memberIds: ["owner_uid"],
        ownerId: "owner_uid",
      });

      const db = getDb({ uid: "other_user" });
      await assertFails(db.collection("rooms").doc("room_1").get());
    });

    it("allows signed-in user to get single candidate inviteCode (collision check)", async () => {
      await seedInviteCode("K7P2QX", { roomId: "room_1", createdAt: new Date() });

      const db = getDb({ uid: "user_a" });
      await assertSucceeds(db.collection("inviteCodes").doc("K7P2QX").get());
      await assertSucceeds(db.collection("inviteCodes").doc("NONEXP").get());
    });

    it("rejects inviteCodes list query, update, and delete", async () => {
      await seedInviteCode("K7P2QX", { roomId: "room_1", createdAt: new Date() });
      const db = getDb({ uid: "user_a" });

      await assertFails(db.collection("inviteCodes").get());
      await assertFails(db.collection("inviteCodes").doc("K7P2QX").update({ roomId: "room_2" }));
      await assertFails(db.collection("inviteCodes").doc("K7P2QX").delete());
    });

    it("rejects modifying parent room protected fields on update", async () => {
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
        members: { user_a: { nickname: "민지", avatarColor: "#FF5722", lastSeenAt: null } },
        placeCount: 0,
      });

      const db = getDb({ uid });

      // Attempting to modify room name or ownerId on update
      await assertFails(db.collection("rooms").doc("room_1").update({ name: "수정제목" }));
      await assertFails(db.collection("rooms").doc("room_1").update({ ownerId: "new_owner" }));
    });
  });

  // -------------------------------------------------------------------
  // 3. Places Subcollection Permissions
  // -------------------------------------------------------------------
  describe("Places Subcollection Permissions", () => {
    it("allows room member to read places and create place in general room and test_room_1", async () => {
      const uid = "user_a";
      const now = new Date();

      // General room
      await seedRoom("room_general_1", {
        name: "부산 여행",
        region: "부산",
        icon: "sea",
        inviteCode: "K7P2QX",
        createdAt: now,
        updatedAt: now,
        memberIds: [uid],
        ownerId: uid,
        members: { user_a: { nickname: "민지", avatarColor: "#FF5722", lastSeenAt: null } },
        placeCount: 0,
      });

      // Existing test_room_1
      await seedRoom("test_room_1", {
        name: "테스트 방",
        region: "부산",
        icon: "sea",
        inviteCode: "TEST01",
        createdAt: now,
        updatedAt: now,
        memberIds: [uid],
        ownerId: uid,
        members: { user_a: { nickname: "민지", avatarColor: "#FF5722", lastSeenAt: null } },
        placeCount: 0,
      });

      const db = getDb({ uid });

      // Create place in general room
      const batch1 = db.batch();
      batch1.set(db.collection("rooms").doc("room_general_1").collection("places").doc("p123"), {
        kakaoPlaceId: "p123",
        kakaoPlaceUrl: "https://place.map.kakao.com/p123",
        searchKeyword: "모모스커피",
        matchType: "manual",
        sourceType: "link",
        addedBy: uid,
        addedByNickname: "민지",
        likedBy: [],
        likeCount: 0,
        createdAt: serverTimestamp(),
      });
      batch1.update(db.collection("rooms").doc("room_general_1"), { placeCount: 1 });
      await assertSucceeds(batch1.commit());

      // Create place in test_room_1
      const batch2 = db.batch();
      batch2.set(db.collection("rooms").doc("test_room_1").collection("places").doc("p124"), {
        kakaoPlaceId: "p124",
        kakaoPlaceUrl: "https://place.map.kakao.com/p124",
        searchKeyword: "이재모피자",
        matchType: "manual",
        sourceType: "link",
        addedBy: uid,
        addedByNickname: "민지",
        likedBy: [],
        likeCount: 0,
        createdAt: serverTimestamp(),
      });
      batch2.update(db.collection("rooms").doc("test_room_1"), { placeCount: 1 });
      await assertSucceeds(batch2.commit());
    });

    it("rejects non-member from creating place in a room", async () => {
      const now = new Date();
      await seedRoom("room_1", {
        name: "부산 여행",
        region: "부산",
        icon: "sea",
        inviteCode: "K7P2QX",
        createdAt: now,
        updatedAt: now,
        memberIds: ["owner_uid"],
        ownerId: "owner_uid",
        members: { owner_uid: { nickname: "주인", avatarColor: "#000000", lastSeenAt: null } },
        placeCount: 0,
      });

      const db = getDb({ uid: "non_member" });
      const batch = db.batch();

      batch.set(db.collection("rooms").doc("room_1").collection("places").doc("p123"), {
        kakaoPlaceId: "p123",
        kakaoPlaceUrl: "https://place.map.kakao.com/p123",
        searchKeyword: "모모스커피",
        matchType: "manual",
        sourceType: "link",
        addedBy: "non_member",
        addedByNickname: "외부인",
        likedBy: [],
        likeCount: 0,
        createdAt: serverTimestamp(),
      });

      batch.update(db.collection("rooms").doc("room_1"), { placeCount: 1 });

      await assertFails(batch.commit());
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
        members: { user_a: { nickname: "민지", avatarColor: "#FF5722", lastSeenAt: null } },
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
        addedBy: "forged_uid",
        addedByNickname: "민지",
        likedBy: [],
        likeCount: 0,
        createdAt: serverTimestamp(),
      });

      batch.update(db.collection("rooms").doc("room_1"), { placeCount: 1 });

      await assertFails(batch.commit());
    });
  });
});
