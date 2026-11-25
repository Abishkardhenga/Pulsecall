const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_android_123";
const BOB_UID = "bob_ios_456";
const CHARLIE_UID = "charlie_intruder_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(msOffset = 1500) {
  return new Date(Date.now() - msOffset);
}

const SAMPLE_OFFER_SDP = "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n";
const SAMPLE_ANSWER_SDP = "v=0\r\no=- 67890 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n";

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
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

test("Unauthenticated context: cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("calls").doc("ROOM12").get());
  await assertFails(unauthDb.collection("calls").get());
  await assertFails(
    unauthDb.collection("calls").doc("ROOM12").set({
      hostUid: ALICE_UID,
      hostDeviceId: "and-device-1",
      hostName: "Alice",
      hostPlatform: "android",
      status: "waiting",
      offerSdp: SAMPLE_OFFER_SDP,
      offerType: "offer",
      offerEpoch: 1,
      answerEpoch: 0,
      renegotiateEpoch: 0,
      callerCandidates: [],
      calleeCandidates: [],
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
});

test("UserProfile: owner can create/update/read; cross-user & ghost fields rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = pastDate(5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice Pixel",
      platform: "android",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Partial update on displayName/platform/updatedAt succeeds
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Alice Studio",
      updatedAt: pastDate(500),
    })
  );

  // Ghost field / role escalation rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: pastDate(200),
    })
  );

  // List users is forbidden (PII/directory scraping protection)
  await assertFails(aliceDb.collection("users").get());

  // Cross-user read/write rejected
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      displayName: "Hijacked",
      updatedAt: pastDate(200),
    })
  );
});

test("CallHistory: owner CRUD & query alignment succeed; cross-user & @DocumentId id injection fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = pastDate(5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("call_history").doc("hist_1").set({
      userId: ALICE_UID,
      roomCode: "A7K9P2",
      peerName: "Bob iPhone",
      peerPlatform: "ios",
      direction: "outgoing",
      status: "completed",
      durationSeconds: 124,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject extra `id` field matching @DocumentId constraint
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("call_history").doc("hist_2").set({
      id: "hist_2",
      userId: ALICE_UID,
      roomCode: "A7K9P2",
      peerName: "Bob iPhone",
      peerPlatform: "ios",
      direction: "outgoing",
      status: "completed",
      durationSeconds: 10,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Owner list query succeeds
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("call_history").get()
  );

  // Cross-user list/get/delete fails
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("call_history").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("call_history").doc("hist_1").delete()
  );
});

test("CallRoom signaling lifecycle: Android host + iOS/Web guest WebRTC handshake, same-UID device handoff, terminal lock, and intruder rejection", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const created = pastDate(10000);

  // 1. Alice (Android Phone) creates signaling room
  await assertSucceeds(
    aliceDb.collection("calls").doc("PULSE1").set({
      hostUid: ALICE_UID,
      hostDeviceId: "and-alice-phone",
      hostName: "Alice Android",
      hostPlatform: "android",
      status: "waiting",
      offerSdp: SAMPLE_OFFER_SDP,
      offerType: "offer",
      offerEpoch: 1,
      answerEpoch: 0,
      renegotiateEpoch: 0,
      callerCandidates: [],
      calleeCandidates: [],
      createdAt: created,
      updatedAt: created,
    })
  );

  // 2. Alice appends host ICE candidate
  await assertSucceeds(
    aliceDb.collection("calls").doc("PULSE1").update({
      callerCandidates: [
        { candidate: "candidate:1 1 UDP 2122252543 192.168.1.10 54321 typ host", sdpMid: "0", sdpMLineIndex: 0 }
      ],
      updatedAt: pastDate(6000),
    })
  );

  // 3. Unfiltered list on /calls is rejected; filtered hostUid query succeeds
  await assertFails(aliceDb.collection("calls").get());
  await assertSucceeds(aliceDb.collection("calls").where("hostUid", "==", ALICE_UID).get());

  // 4. Bob (iOS) fetches room by exact code and joins with SDP answer
  await assertSucceeds(bobDb.collection("calls").doc("PULSE1").get());
  await assertSucceeds(
    bobDb.collection("calls").doc("PULSE1").update({
      guestUid: BOB_UID,
      guestDeviceId: "ios-bob-iphone",
      guestName: "Bob iPhone",
      guestPlatform: "ios",
      answerSdp: SAMPLE_ANSWER_SDP,
      answerType: "answer",
      answerEpoch: 1,
      status: "connected",
      updatedAt: pastDate(5000),
    })
  );

  // 5. Bob appends callee ICE candidate
  await assertSucceeds(
    bobDb.collection("calls").doc("PULSE1").update({
      calleeCandidates: [
        { candidate: "candidate:2 1 UDP 2122252543 192.168.1.20 54322 typ host", sdpMid: "0", sdpMLineIndex: 0 }
      ],
      updatedAt: pastDate(4500),
    })
  );

  // 6. Alice switches from Android phone to Laptop Web with same UID (Action 4)
  await assertSucceeds(
    aliceDb.collection("calls").doc("PULSE1").update({
      hostDeviceId: "web-alice-laptop",
      hostName: "Alice Laptop",
      hostPlatform: "web",
      offerSdp: SAMPLE_OFFER_SDP,
      offerType: "offer",
      offerEpoch: 2,
      answerEpoch: 0,
      callerCandidates: [],
      calleeCandidates: [],
      updatedAt: pastDate(4000),
    })
  );

  // 7. Bob sends fresh SDP answer for offerEpoch 2 (Action 5b)
  await assertSucceeds(
    bobDb.collection("calls").doc("PULSE1").update({
      guestDeviceId: "ios-bob-iphone",
      guestName: "Bob iPhone",
      guestPlatform: "ios",
      answerSdp: SAMPLE_ANSWER_SDP,
      answerType: "answer",
      answerEpoch: 2,
      calleeCandidates: [],
      status: "connected",
      updatedAt: pastDate(3500),
    })
  );

  // 8. Bob switches from iPhone to Laptop Web with same UID (Action 5a -> Host Action 4 -> Guest Action 5b)
  await assertSucceeds(
    bobDb.collection("calls").doc("PULSE1").update({
      guestDeviceId: "web-bob-laptop",
      guestName: "Bob Laptop",
      guestPlatform: "web",
      renegotiateEpoch: 1,
      answerEpoch: 0,
      calleeCandidates: [],
      updatedAt: pastDate(3000),
    })
  );
  await assertSucceeds(
    aliceDb.collection("calls").doc("PULSE1").update({
      hostDeviceId: "web-alice-laptop",
      hostName: "Alice Laptop",
      hostPlatform: "web",
      offerSdp: SAMPLE_OFFER_SDP,
      offerType: "offer",
      offerEpoch: 3,
      answerEpoch: 0,
      callerCandidates: [],
      calleeCandidates: [],
      updatedAt: pastDate(2500),
    })
  );
  await assertSucceeds(
    bobDb.collection("calls").doc("PULSE1").update({
      guestDeviceId: "web-bob-laptop",
      guestName: "Bob Laptop",
      guestPlatform: "web",
      answerSdp: SAMPLE_ANSWER_SDP,
      answerType: "answer",
      answerEpoch: 3,
      calleeCandidates: [],
      status: "connected",
      updatedAt: pastDate(2000),
    })
  );

  // 9. Intruder Charlie cannot hijack connected room or spoof Bob's handoff
  await assertFails(
    charlieDb.collection("calls").doc("PULSE1").update({
      guestUid: CHARLIE_UID,
      guestDeviceId: "and-charlie",
      guestName: "Charlie",
      guestPlatform: "android",
      answerSdp: SAMPLE_ANSWER_SDP,
      answerType: "answer",
      answerEpoch: 3,
      status: "connected",
      updatedAt: pastDate(1500),
    })
  );
  await assertFails(
    charlieDb.collection("calls").doc("PULSE1").update({
      status: "ended",
      updatedAt: pastDate(1500),
    })
  );

  // 10. Bob cannot overwrite Alice's offerSdp or hostUid
  await assertFails(
    bobDb.collection("calls").doc("PULSE1").update({
      offerSdp: "tampered_sdp_payload",
      offerEpoch: 4,
      updatedAt: pastDate(1200),
    })
  );

  // 11. Participant ends the call -> terminal state locked
  await assertSucceeds(
    aliceDb.collection("calls").doc("PULSE1").update({
      status: "ended",
      updatedAt: pastDate(1000),
    })
  );

  // 12. Subsequent updates after terminal state 'ended' are rejected
  await assertFails(
    bobDb.collection("calls").doc("PULSE1").update({
      status: "connected",
      updatedAt: pastDate(500),
    })
  );
  await assertFails(
    aliceDb.collection("calls").doc("PULSE1").update({
      callerCandidates: [],
      updatedAt: pastDate(500),
    })
  );

  // 13. Tampered future timestamp rejected
  await assertFails(
    aliceDb.collection("calls").doc("FUTURE1").set({
      hostUid: ALICE_UID,
      hostDeviceId: "and-alice-phone",
      hostName: "Alice",
      hostPlatform: "android",
      status: "waiting",
      offerSdp: SAMPLE_OFFER_SDP,
      offerType: "offer",
      offerEpoch: 1,
      answerEpoch: 0,
      renegotiateEpoch: 0,
      callerCandidates: [],
      calleeCandidates: [],
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );
});
