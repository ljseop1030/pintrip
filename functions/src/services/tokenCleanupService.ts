import * as admin from "firebase-admin";

/**
 * 만료되거나 등록 해제된 FCM 토큰을 트랜잭션으로 원자적으로 정리합니다.
 * 읽기와 삭제 사이의 경쟁 상태(Race Condition)를 방지하기 위해 Firestore 트랜잭션을 사용하며,
 * 현재 문서의 token 값이 실패한 failedToken과 일치할 때만 원자적으로 삭제합니다.
 */
export async function cleanUpInvalidToken(
  db: admin.firestore.Firestore,
  recipientUid: string,
  installationId: string,
  failedToken: string,
  externalTransaction?: admin.firestore.Transaction
): Promise<boolean> {
  if (!recipientUid || !installationId || !failedToken) return false;

  const deviceDocRef = db
    .collection("users")
    .doc(recipientUid)
    .collection("devices")
    .doc(installationId);

  if (externalTransaction) {
    const docSnap = await externalTransaction.get(deviceDocRef);
    if (!docSnap.exists) return false;
    const currentToken = docSnap.data()?.token;
    if (currentToken === failedToken) {
      externalTransaction.delete(deviceDocRef);
      return true;
    }
    return false;
  }

  try {
    const deleted = await db.runTransaction(async (transaction) => {
      const docSnap = await transaction.get(deviceDocRef);
      if (!docSnap.exists) {
        return false;
      }

      const currentToken = docSnap.data()?.token;
      if (currentToken === failedToken) {
        transaction.delete(deviceDocRef);
        return true;
      } else {
        return false;
      }
    });

    if (deleted) {
      console.log(`Successfully cleaned up stale token for user: ${recipientUid}, device: ${installationId}`);
    } else {
      console.log(`Skipped stale token cleanup: token was updated for user: ${recipientUid}, device: ${installationId}`);
    }
    return deleted;
  } catch (error) {
    console.warn(`Failed to clean up stale token in transaction for user: ${recipientUid}, device: ${installationId}:`, error);
    return false;
  }
}
