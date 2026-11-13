const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require("@firebase/rules-unit-testing");
const { serverTimestamp, deleteField, arrayUnion } = require("firebase/firestore");
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

    it("rejects modifying parent room protected fields on update even when placeCount is incremented", async () => {
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

      // Attempting to increment placeCount AND modify protected fields (name, ownerId, or region)
      await assertFails(
        db.collection("rooms").doc("room_1").update({
          placeCount: 1,
          name: "수정제목",
        })
      );

      await assertFails(
        db.collection("rooms").doc("room_1").update({
          placeCount: 1,
          ownerId: "new_owner",
        })
      );
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

      // Assert place read (single doc & collection query)
      const placeSnap = await db.collection("rooms").doc("room_general_1").collection("places").doc("p123").get();
      assert.strictEqual(placeSnap.exists, true);
      assert.strictEqual(placeSnap.data().searchKeyword, "모모스커피");

      const placesListSnap = await db.collection("rooms").doc("room_general_1").collection("places").get();
      assert.strictEqual(placesListSnap.docs.length, 1);

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

      // Assert test_room_1 place read
      const testRoomPlaceSnap = await db.collection("rooms").doc("test_room_1").collection("places").doc("p124").get();
      assert.strictEqual(testRoomPlaceSnap.exists, true);
      assert.strictEqual(testRoomPlaceSnap.data().searchKeyword, "이재모피자");
    });

    it("rejects non-member from reading places or creating place in a room", async () => {
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

      // Reject non-member reading places
      await assertFails(db.collection("rooms").doc("room_1").collection("places").doc("p123").get());
      await assertFails(db.collection("rooms").doc("room_1").collection("places").get());

      // Reject non-member creating place
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

  // -------------------------------------------------------------------
  // 4. Room Join & Bidirectional Proof Verification
  // -------------------------------------------------------------------
  describe("Room Join & Bidirectional Proof Verification", () => {
    it("allows non-member to join room with atomic batch proof creation and verifies members/memberIds/updatedAt", async () => {
      const ownerUid = "owner_a";
      const joinUid = "user_b";
      const code = "K7P2QX";
      const roomId = "room_join_1";
      const pastTime = new Date(Date.now() - 100000);

      await seedUserProfile(ownerUid, "방장", "owner@test.com", "#FF5722");
      await seedUserProfile(joinUid, "철수", "chulsoo@test.com", "#00BCD4");

      await seedRoom(roomId, {
        name: "제주 여행",
        region: "제주",
        startDate: null,
        endDate: null,
        icon: "healing",
        inviteCode: code,
        ownerId: ownerUid,
        memberIds: [ownerUid],
        members: {
          owner_a: { nickname: "방장", avatarColor: "#FF5722", lastSeenAt: null },
        },
        placeCount: 0,
        createdAt: pastTime,
        updatedAt: pastTime,
      });

      await seedInviteCode(code, { roomId: roomId, createdAt: pastTime });

      const db = getDb({ uid: joinUid, token: { email: "chulsoo@test.com" } });
      const batch = db.batch();

      const proofRef = db.collection("inviteCodes").doc(code).collection("joinProofs").doc(joinUid);
      const roomRef = db.collection("rooms").doc(roomId);

      batch.set(proofRef, { joinedAt: serverTimestamp() });
      batch.update(roomRef, {
        memberIds: [ownerUid, joinUid],
        "members.user_b": { nickname: "철수", avatarColor: "#00BCD4", lastSeenAt: null },
        updatedAt: serverTimestamp(),
      });

      await assertSucceeds(batch.commit());

      // Read back proof and room documents using admin context to assert timestamp equality
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        const proofSnap = await adminDb.collection("inviteCodes").doc(code).collection("joinProofs").doc(joinUid).get();
        const roomSnap = await adminDb.collection("rooms").doc(roomId).get();

        assert.strictEqual(proofSnap.exists, true);
        assert.ok(proofSnap.data().joinedAt != null);
        assert.ok(typeof proofSnap.data().joinedAt.isEqual === "function");
        assert.ok(proofSnap.data().joinedAt.isEqual(roomSnap.data().updatedAt));
      });

      // Read back room document using member's context to assert field values & timestamp update
      const roomSnap = await db.collection("rooms").doc(roomId).get();
      assert.strictEqual(roomSnap.exists, true);
      assert.strictEqual(roomSnap.data().memberIds.length, 2);
      assert.deepStrictEqual(roomSnap.data().memberIds, [ownerUid, joinUid]);
      assert.strictEqual(roomSnap.data().members.owner_a.nickname, "방장");
      assert.strictEqual(roomSnap.data().members.owner_a.avatarColor, "#FF5722");
      assert.strictEqual(roomSnap.data().members.owner_a.lastSeenAt, null);
      assert.strictEqual(roomSnap.data().members.user_b.nickname, "철수");
      assert.strictEqual(roomSnap.data().members.user_b.avatarColor, "#00BCD4");
      assert.strictEqual(roomSnap.data().members.user_b.lastSeenAt, null);
      assert.ok(roomSnap.data().updatedAt.toDate().getTime() > pastTime.getTime());
    });

    it("allows multiple users to join room concurrently and asserts all members are preserved", async () => {
      const ownerUid = "owner_a";
      const joinUidB = "user_b";
      const joinUidC = "user_c";
      const code = "K7P2QX";
      const roomId = "room_concurrent_join";
      const pastTime = new Date(Date.now() - 100000);

      await seedUserProfile(ownerUid, "방장", "owner@test.com", "#FF5722");
      await seedUserProfile(joinUidB, "철수", "chulsoo@test.com", "#00BCD4");
      await seedUserProfile(joinUidC, "영희", "younghee@test.com", "#E91E63");

      await seedRoom(roomId, {
        name: "강릉 여행",
        region: "강릉",
        startDate: null,
        endDate: null,
        icon: "sea",
        inviteCode: code,
        ownerId: ownerUid,
        memberIds: [ownerUid],
        members: {
          owner_a: { nickname: "방장", avatarColor: "#FF5722", lastSeenAt: null },
        },
        placeCount: 0,
        createdAt: pastTime,
        updatedAt: pastTime,
      });

      await seedInviteCode(code, { roomId: roomId, createdAt: pastTime });

      // User B batch
      const dbB = getDb({ uid: joinUidB });
      const batchB = dbB.batch();
      batchB.set(dbB.collection("inviteCodes").doc(code).collection("joinProofs").doc(joinUidB), { joinedAt: serverTimestamp() });
      batchB.update(dbB.collection("rooms").doc(roomId), {
        memberIds: arrayUnion(joinUidB),
        "members.user_b": { nickname: "철수", avatarColor: "#00BCD4", lastSeenAt: null },
        updatedAt: serverTimestamp(),
      });

      // User C batch
      const dbC = getDb({ uid: joinUidC });
      const batchC = dbC.batch();
      batchC.set(dbC.collection("inviteCodes").doc(code).collection("joinProofs").doc(joinUidC), { joinedAt: serverTimestamp() });
      batchC.update(dbC.collection("rooms").doc(roomId), {
        memberIds: arrayUnion(joinUidC),
        "members.user_c": { nickname: "영희", avatarColor: "#E91E63", lastSeenAt: null },
        updatedAt: serverTimestamp(),
      });

      // Execute concurrently
      await assertSucceeds(Promise.all([batchB.commit(), batchC.commit()]));

      // Assert that all 3 members (owner, B, C) are present and intact
      const dbOwner = getDb({ uid: ownerUid });
      const roomSnap = await dbOwner.collection("rooms").doc(roomId).get();
      assert.strictEqual(roomSnap.exists, true);
      const memberList = roomSnap.data().memberIds;
      assert.strictEqual(memberList.length, 3);
      assert.ok(memberList.includes(ownerUid));
      assert.ok(memberList.includes(joinUidB));
      assert.ok(memberList.includes(joinUidC));
      assert.strictEqual(roomSnap.data().members.owner_a.nickname, "방장");
      assert.strictEqual(roomSnap.data().members.user_b.nickname, "철수");
      assert.strictEqual(roomSnap.data().members.user_c.nickname, "영희");
    });

    it("rejects non-member join without atomic proof creation (knowing roomId only)", async () => {
      const ownerUid = "owner_a";
      const joinUid = "user_attacker";
      const code = "K7P2QX";
      const roomId = "room_secure";
      const now = new Date();

      await seedUserProfile(ownerUid, "방장", "owner@test.com", "#FF5722");
      await seedUserProfile(joinUid, "해커", "hacker@test.com", "#000000");

      await seedRoom(roomId, {
        name: "비밀 여행",
        region: "서울",
        startDate: null,
        endDate: null,
        icon: "city",
        inviteCode: code,
        ownerId: ownerUid,
        memberIds: [ownerUid],
        members: { owner_a: { nickname: "방장", avatarColor: "#FF5722", lastSeenAt: null } },
        placeCount: 0,
        createdAt: now,
        updatedAt: now,
      });

      await seedInviteCode(code, { roomId: roomId, createdAt: now });

      const db = getDb({ uid: joinUid });

      // Attack: Attempt to update room directly without setting joinProofs doc
      await assertFails(
        db.collection("rooms").doc(roomId).update({
          memberIds: [ownerUid, joinUid],
          "members.user_attacker": { nickname: "해커", avatarColor: "#000000", lastSeenAt: null },
          updatedAt: serverTimestamp(),
        })
      );
    });

    it("rejects standalone proof creation without room membership addition", async () => {
      const ownerUid = "owner_a";
      const joinUid = "user_b";
      const code = "K7P2QX";
      const roomId = "room_proof_only";
      const now = new Date();

      await seedUserProfile(ownerUid, "방장", "owner@test.com", "#FF5722");
      await seedUserProfile(joinUid, "철수", "chulsoo@test.com", "#00BCD4");

      await seedRoom(roomId, {
        name: "서울 여행",
        region: "서울",
        startDate: null,
        endDate: null,
        icon: "city",
        inviteCode: code,
        ownerId: ownerUid,
        memberIds: [ownerUid],
        members: { owner_a: { nickname: "방장", avatarColor: "#FF5722", lastSeenAt: null } },
        placeCount: 0,
        createdAt: now,
        updatedAt: now,
      });

      await seedInviteCode(code, { roomId: roomId, createdAt: now });

      const db = getDb({ uid: joinUid });
      const proofRef = db.collection("inviteCodes").doc(code).collection("joinProofs").doc(joinUid);

      // Attempt to create proof doc standalone without room update
      await assertFails(
        proofRef.set({ joinedAt: serverTimestamp() })
      );
    });

    it("rejects reusing proof for a different code or room or uid and verifies correct match succeeds", async () => {
      const ownerUid = "owner_a";
      const joinUid = "user_b";
      const codeTarget = "K7P2QX";
      const codeOther = "B8R3SY";
      const roomId = "room_target";
      const roomIdOther = "room_other";
      const now = new Date();

      await seedUserProfile(ownerUid, "방장", "owner@test.com", "#FF5722");
      await seedUserProfile(joinUid, "철수", "chulsoo@test.com", "#00BCD4");

      await seedRoom(roomId, {
        name: "서울 여행",
        region: "서울",
        startDate: null,
        endDate: null,
        icon: "city",
        inviteCode: codeTarget,
        ownerId: ownerUid,
        memberIds: [ownerUid],
        members: { owner_a: { nickname: "방장", avatarColor: "#FF5722", lastSeenAt: null } },
        placeCount: 0,
        createdAt: now,
        updatedAt: now,
      });

      await seedRoom(roomIdOther, {
        name: "다른 여행",
        region: "부산",
        startDate: null,
        endDate: null,
        icon: "sea",
        inviteCode: codeOther,
        ownerId: ownerUid,
        memberIds: [ownerUid],
        members: { owner_a: { nickname: "방장", avatarColor: "#FF5722", lastSeenAt: null } },
        placeCount: 0,
        createdAt: now,
        updatedAt: now,
      });

      await seedInviteCode(codeTarget, { roomId: roomId, createdAt: now });
      await seedInviteCode(codeOther, { roomId: roomIdOther, createdAt: now });

      const db = getDb({ uid: joinUid });

      // Mismatched proof code (B8R3SY instead of K7P2QX for room_target)
      const batchMismatched = db.batch();
      batchMismatched.set(db.collection("inviteCodes").doc(codeOther).collection("joinProofs").doc(joinUid), { joinedAt: serverTimestamp() });
      batchMismatched.update(db.collection("rooms").doc(roomId), {
        memberIds: [ownerUid, joinUid],
        "members.user_b": { nickname: "철수", avatarColor: "#00BCD4", lastSeenAt: null },
        updatedAt: serverTimestamp(),
      });
      await assertFails(batchMismatched.commit());

      // Contrast: Matched proof code (K7P2QX for room_target) succeeds
      const batchMatched = db.batch();
      batchMatched.set(db.collection("inviteCodes").doc(codeTarget).collection("joinProofs").doc(joinUid), { joinedAt: serverTimestamp() });
      batchMatched.update(db.collection("rooms").doc(roomId), {
        memberIds: [ownerUid, joinUid],
        "members.user_b": { nickname: "철수", avatarColor: "#00BCD4", lastSeenAt: null },
        updatedAt: serverTimestamp(),
      });
      await assertSucceeds(batchMatched.commit());
    });

    it("rejects client reading, updating, deleting, or listing EXISTING joinProofs", async () => {
      const code = "K7P2QX";
      const uid = "user_a";

      // Seed actual proof document via disabled rules context
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const db = context.firestore();
        await db.collection("inviteCodes").doc(code).collection("joinProofs").doc(uid).set({
          joinedAt: new Date(),
        });
      });

      const db = getDb({ uid: uid });
      const proofRef = db.collection("inviteCodes").doc(code).collection("joinProofs").doc(uid);

      await assertFails(proofRef.get());
      await assertFails(db.collection("inviteCodes").doc(code).collection("joinProofs").get());
      await assertFails(proofRef.update({ joinedAt: new Date() }));
      await assertFails(proofRef.delete());
    });

    it("rejects existing room member or creator from creating joinProofs document", async () => {
      const ownerUid = "owner_a";
      const code = "K7P2QX";
      const roomId = "room_existing_proof_attempt";
      const now = new Date();

      await seedUserProfile(ownerUid, "방장", "owner@test.com", "#FF5722");
      await seedRoom(roomId, {
        name: "서울 여행",
        region: "서울",
        startDate: null,
        endDate: null,
        icon: "city",
        inviteCode: code,
        ownerId: ownerUid,
        memberIds: [ownerUid],
        members: { owner_a: { nickname: "방장", avatarColor: "#FF5722", lastSeenAt: null } },
        placeCount: 0,
        createdAt: now,
        updatedAt: now,
      });
      await seedInviteCode(code, { roomId: roomId, createdAt: now });

      const db = getDb({ uid: ownerUid });
      const proofRef = db.collection("inviteCodes").doc(code).collection("joinProofs").doc(ownerUid);

      // Existing member attempting to create proof doc standalone or during update
      await assertFails(proofRef.set({ joinedAt: serverTimestamp() }));
    });

    it("rejects join using a pre-seeded past proof document without creating a fresh atomic proof", async () => {
      const ownerUid = "owner_a";
      const joinUid = "user_b";
      const code = "K7P2QX";
      const roomId = "room_past_proof";
      const now = new Date();

      await seedUserProfile(ownerUid, "방장", "owner@test.com", "#FF5722");
      await seedUserProfile(joinUid, "철수", "chulsoo@test.com", "#00BCD4");

      await seedRoom(roomId, {
        name: "서울 여행",
        region: "서울",
        startDate: null,
        endDate: null,
        icon: "city",
        inviteCode: code,
        ownerId: ownerUid,
        memberIds: [ownerUid],
        members: { owner_a: { nickname: "방장", avatarColor: "#FF5722", lastSeenAt: null } },
        placeCount: 0,
        createdAt: now,
        updatedAt: now,
      });
      await seedInviteCode(code, { roomId: roomId, createdAt: now });

      // Seed past proof doc
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const db = context.firestore();
        await db.collection("inviteCodes").doc(code).collection("joinProofs").doc(joinUid).set({
          joinedAt: new Date(Date.now() - 10000),
        });
      });

      const db = getDb({ uid: joinUid });

      // Attempt to join using past proof without setting fresh proof in same batch
      await assertFails(
        db.collection("rooms").doc(roomId).update({
          memberIds: [ownerUid, joinUid],
          "members.user_b": { nickname: "철수", avatarColor: "#00BCD4", lastSeenAt: null },
          updatedAt: serverTimestamp(),
        })
      );
    });

    it("rejects join if existing member's nickname, avatarColor, or lastSeenAt is altered", async () => {
      const ownerUid = "owner_a";
      const joinUid = "user_b";
      const code = "K7P2QX";
      const roomId = "room_alter_existing";
      const now = new Date();

      await seedUserProfile(ownerUid, "방장", "owner@test.com", "#FF5722");
      await seedUserProfile(joinUid, "철수", "chulsoo@test.com", "#00BCD4");

      await seedRoom(roomId, {
        name: "서울 여행",
        region: "서울",
        startDate: null,
        endDate: null,
        icon: "city",
        inviteCode: code,
        ownerId: ownerUid,
        memberIds: [ownerUid],
        members: { owner_a: { nickname: "방장", avatarColor: "#FF5722", lastSeenAt: null } },
        placeCount: 0,
        createdAt: now,
        updatedAt: now,
      });
      await seedInviteCode(code, { roomId: roomId, createdAt: now });

      const db = getDb({ uid: joinUid });

      // Attack: Attempt to alter existing member's nickname
      const batch1 = db.batch();
      batch1.set(db.collection("inviteCodes").doc(code).collection("joinProofs").doc(joinUid), { joinedAt: serverTimestamp() });
      batch1.update(db.collection("rooms").doc(roomId), {
        memberIds: [ownerUid, joinUid],
        "members.owner_a": { nickname: "위조방장", avatarColor: "#FF5722", lastSeenAt: null },
        "members.user_b": { nickname: "철수", avatarColor: "#00BCD4", lastSeenAt: null },
        updatedAt: serverTimestamp(),
      });
      await assertFails(batch1.commit());

      // Attack: Attempt to alter existing member's avatarColor
      const batch2 = db.batch();
      batch2.set(db.collection("inviteCodes").doc(code).collection("joinProofs").doc(joinUid), { joinedAt: serverTimestamp() });
      batch2.update(db.collection("rooms").doc(roomId), {
        memberIds: [ownerUid, joinUid],
        "members.owner_a": { nickname: "방장", avatarColor: "#000000", lastSeenAt: null },
        "members.user_b": { nickname: "철수", avatarColor: "#00BCD4", lastSeenAt: null },
        updatedAt: serverTimestamp(),
      });
      await assertFails(batch2.commit());

      // Attack: Attempt to alter existing member's lastSeenAt
      const batch3 = db.batch();
      batch3.set(db.collection("inviteCodes").doc(code).collection("joinProofs").doc(joinUid), { joinedAt: serverTimestamp() });
      batch3.update(db.collection("rooms").doc(roomId), {
        memberIds: [ownerUid, joinUid],
        "members.owner_a": { nickname: "방장", avatarColor: "#FF5722", lastSeenAt: serverTimestamp() },
        "members.user_b": { nickname: "철수", avatarColor: "#00BCD4", lastSeenAt: null },
        updatedAt: serverTimestamp(),
      });
      await assertFails(batch3.commit());
    });

    it("rejects join if arbitrary top-level room field is added or existing field omitted", async () => {
      const ownerUid = "owner_a";
      const joinUid = "user_b";
      const code = "K7P2QX";
      const roomId = "room_hacked_field";
      const now = new Date();

      await seedUserProfile(ownerUid, "방장", "owner@test.com", "#FF5722");
      await seedUserProfile(joinUid, "철수", "chulsoo@test.com", "#00BCD4");

      await seedRoom(roomId, {
        name: "서울 여행",
        region: "서울",
        startDate: null,
        endDate: null,
        icon: "city",
        inviteCode: code,
        ownerId: ownerUid,
        memberIds: [ownerUid],
        members: { owner_a: { nickname: "방장", avatarColor: "#FF5722", lastSeenAt: null } },
        placeCount: 0,
        createdAt: now,
        updatedAt: now,
      });
      await seedInviteCode(code, { roomId: roomId, createdAt: now });

      const db = getDb({ uid: joinUid });

      // Attack: Add hackedField: true during join
      const batch1 = db.batch();
      batch1.set(db.collection("inviteCodes").doc(code).collection("joinProofs").doc(joinUid), { joinedAt: serverTimestamp() });
      batch1.update(db.collection("rooms").doc(roomId), {
        memberIds: [ownerUid, joinUid],
        "members.user_b": { nickname: "철수", avatarColor: "#00BCD4", lastSeenAt: null },
        updatedAt: serverTimestamp(),
        hackedField: true,
      });
      await assertFails(batch1.commit());

      // Attack: Delete region field during join
      const batch2 = db.batch();
      batch2.set(db.collection("inviteCodes").doc(code).collection("joinProofs").doc(joinUid), { joinedAt: serverTimestamp() });
      batch2.update(db.collection("rooms").doc(roomId), {
        memberIds: [ownerUid, joinUid],
        "members.user_b": { nickname: "철수", avatarColor: "#00BCD4", lastSeenAt: null },
        updatedAt: serverTimestamp(),
        region: deleteField(),
      });
      await assertFails(batch2.commit());
    });

    it("rejects join if user profile nickname or avatarColor does not match users collection", async () => {
      const ownerUid = "owner_a";
      const joinUid = "user_b";
      const code = "K7P2QX";
      const roomId = "room_fake_profile";
      const now = new Date();

      await seedUserProfile(ownerUid, "방장", "owner@test.com", "#FF5722");
      await seedUserProfile(joinUid, "진짜닉네임", "chulsoo@test.com", "#00BCD4");

      await seedRoom(roomId, {
        name: "서울 여행",
        region: "서울",
        startDate: null,
        endDate: null,
        icon: "city",
        inviteCode: code,
        ownerId: ownerUid,
        memberIds: [ownerUid],
        members: { owner_a: { nickname: "방장", avatarColor: "#FF5722", lastSeenAt: null } },
        placeCount: 0,
        createdAt: now,
        updatedAt: now,
      });

      await seedInviteCode(code, { roomId: roomId, createdAt: now });

      const db = getDb({ uid: joinUid });

      // Attempt join with forged nickname
      const batch1 = db.batch();
      batch1.set(db.collection("inviteCodes").doc(code).collection("joinProofs").doc(joinUid), { joinedAt: serverTimestamp() });
      batch1.update(db.collection("rooms").doc(roomId), {
        memberIds: [ownerUid, joinUid],
        "members.user_b": { nickname: "위조닉네임", avatarColor: "#00BCD4", lastSeenAt: null },
        updatedAt: serverTimestamp(),
      });
      await assertFails(batch1.commit());

      // Attempt join with forged avatarColor
      const batch2 = db.batch();
      batch2.set(db.collection("inviteCodes").doc(code).collection("joinProofs").doc(joinUid), { joinedAt: serverTimestamp() });
      batch2.update(db.collection("rooms").doc(roomId), {
        memberIds: [ownerUid, joinUid],
        "members.user_b": { nickname: "진짜닉네임", avatarColor: "#112233", lastSeenAt: null },
        updatedAt: serverTimestamp(),
      });
      await assertFails(batch2.commit());
    });

    it("rejects join if protected room fields are altered (ownerId, name, region, icon, placeCount, etc.)", async () => {
      const ownerUid = "owner_a";
      const joinUid = "user_b";
      const code = "K7P2QX";
      const roomId = "room_protected_fields";
      const now = new Date();

      await seedUserProfile(ownerUid, "방장", "owner@test.com", "#FF5722");
      await seedUserProfile(joinUid, "철수", "chulsoo@test.com", "#00BCD4");

      await seedRoom(roomId, {
        name: "서울 여행",
        region: "서울",
        startDate: null,
        endDate: null,
        icon: "city",
        inviteCode: code,
        ownerId: ownerUid,
        memberIds: [ownerUid],
        members: { owner_a: { nickname: "방장", avatarColor: "#FF5722", lastSeenAt: null } },
        placeCount: 0,
        createdAt: now,
        updatedAt: now,
      });

      await seedInviteCode(code, { roomId: roomId, createdAt: now });

      const db = getDb({ uid: joinUid });

      // Attempt to hijack ownership during join
      const batch = db.batch();
      batch.set(db.collection("inviteCodes").doc(code).collection("joinProofs").doc(joinUid), { joinedAt: serverTimestamp() });
      batch.update(db.collection("rooms").doc(roomId), {
        memberIds: [ownerUid, joinUid],
        "members.user_b": { nickname: "철수", avatarColor: "#00BCD4", lastSeenAt: null },
        ownerId: joinUid, // Forged ownerId
        updatedAt: serverTimestamp(),
      });

      await assertFails(batch.commit());
    });
  });

  // -------------------------------------------------------------------
  // 5. Place Addition updatedAt & Team Compatibility Regression Tests
  // -------------------------------------------------------------------
  describe("Place Addition updatedAt & Team Compatibility Regression Tests", () => {
    it("allows existing member to add place with placeCount increment AND server updatedAt (New App) and asserts document values", async () => {
      const uid = "user_a";
      const roomId = "room_new_app_places";
      const pastTime = new Date(Date.now() - 100000);

      await seedRoom(roomId, {
        name: "부산 여행",
        region: "부산",
        icon: "sea",
        inviteCode: "K7P2QX",
        createdAt: pastTime,
        updatedAt: pastTime,
        memberIds: [uid],
        ownerId: uid,
        members: { user_a: { nickname: "민지", avatarColor: "#FF5722", lastSeenAt: null } },
        placeCount: 0,
      });

      const db = getDb({ uid });
      const batch = db.batch();

      batch.set(db.collection("rooms").doc(roomId).collection("places").doc("p200"), {
        kakaoPlaceId: "p200",
        kakaoPlaceUrl: "https://place.map.kakao.com/p200",
        searchKeyword: "모모스커피",
        matchType: "manual",
        sourceType: "link",
        addedBy: uid,
        addedByNickname: "민지",
        likedBy: [],
        likeCount: 0,
        createdAt: serverTimestamp(),
      });

      batch.update(db.collection("rooms").doc(roomId), {
        placeCount: 1,
        updatedAt: serverTimestamp(),
      });

      await assertSucceeds(batch.commit());

      // Assert place document created and room placeCount/updatedAt updated
      const placeSnap = await db.collection("rooms").doc(roomId).collection("places").doc("p200").get();
      assert.strictEqual(placeSnap.exists, true);
      assert.strictEqual(placeSnap.data().searchKeyword, "모모스커피");

      const roomSnap = await db.collection("rooms").doc(roomId).get();
      assert.strictEqual(roomSnap.data().placeCount, 1);
      assert.ok(roomSnap.data().updatedAt.toDate().getTime() > pastTime.getTime());
    });

    it("allows existing member to add place with placeCount increment ONLY (Legacy App Compatibility) and asserts updatedAt is preserved", async () => {
      const uid = "user_a";
      const roomId = "room_legacy_app_places";
      const now = new Date();

      await seedRoom(roomId, {
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

      batch.set(db.collection("rooms").doc(roomId).collection("places").doc("p201"), {
        kakaoPlaceId: "p201",
        kakaoPlaceUrl: "https://place.map.kakao.com/p201",
        searchKeyword: "이재모피자",
        matchType: "manual",
        sourceType: "link",
        addedBy: uid,
        addedByNickname: "민지",
        likedBy: [],
        likeCount: 0,
        createdAt: serverTimestamp(),
      });

      batch.update(db.collection("rooms").doc(roomId), {
        placeCount: 1,
      });

      await assertSucceeds(batch.commit());

      // Assert place document created and room placeCount updated while updatedAt is preserved
      const placeSnap = await db.collection("rooms").doc(roomId).collection("places").doc("p201").get();
      assert.strictEqual(placeSnap.exists, true);

      const roomSnap = await db.collection("rooms").doc(roomId).get();
      assert.strictEqual(roomSnap.data().placeCount, 1);
      assert.strictEqual(roomSnap.data().updatedAt.toDate().getTime(), now.getTime());
    });

    it("rejects updating updatedAt alone without placeCount increment", async () => {
      const uid = "user_a";
      const roomId = "room_updatedat_only";
      const now = new Date();

      await seedRoom(roomId, {
        name: "부산 여행",
        region: "부산",
        icon: "sea",
        inviteCode: "K7P2QX",
        createdAt: now,
        updatedAt: now,
        memberIds: [uid],
        ownerId: uid,
        members: { user_a: { nickname: "민지", avatarColor: "#FF5722", lastSeenAt: null } },
        placeCount: 5,
      });

      const db = getDb({ uid });

      await assertFails(
        db.collection("rooms").doc(roomId).update({
          updatedAt: serverTimestamp(),
        })
      );
    });

    it("rejects updating updatedAt to arbitrary non-server timestamp", async () => {
      const uid = "user_a";
      const roomId = "room_fake_time";
      const now = new Date();

      await seedRoom(roomId, {
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

      batch.set(db.collection("rooms").doc(roomId).collection("places").doc("p202"), {
        kakaoPlaceId: "p202",
        kakaoPlaceUrl: "https://place.map.kakao.com/p202",
        searchKeyword: "해운대암소갈비",
        matchType: "manual",
        sourceType: "link",
        addedBy: uid,
        addedByNickname: "민지",
        likedBy: [],
        likeCount: 0,
        createdAt: serverTimestamp(),
      });

      // Attempting to pass a non-server timestamp for updatedAt
      batch.update(db.collection("rooms").doc(roomId), {
        placeCount: 1,
        updatedAt: new Date(),
      });

      await assertFails(batch.commit());
    });

    it("rejects non-member from using both new and legacy place addition update modes", async () => {
      const ownerUid = "owner_a";
      const nonMemberUid = "non_member";
      const roomId = "room_non_member_place";
      const now = new Date();

      await seedRoom(roomId, {
        name: "부산 여행",
        region: "부산",
        icon: "sea",
        inviteCode: "K7P2QX",
        createdAt: now,
        updatedAt: now,
        memberIds: [ownerUid],
        ownerId: ownerUid,
        members: { owner_a: { nickname: "주인", avatarColor: "#000000", lastSeenAt: null } },
        placeCount: 0,
      });

      const db = getDb({ uid: nonMemberUid });

      // Legacy mode attempt by non-member
      await assertFails(
        db.collection("rooms").doc(roomId).update({ placeCount: 1 })
      );

      // New mode attempt by non-member
      await assertFails(
        db.collection("rooms").doc(roomId).update({ placeCount: 1, updatedAt: serverTimestamp() })
      );
    });
  });
});
