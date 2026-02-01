import * as functions from "firebase-functions";
import * as admin from "firebase-admin";

admin.initializeApp();

export const removeFriend = functions.https.onCall(
  async (data, context) => {

    const myUid = context.auth?.uid;
    const friendUid = data.friendUid as string;

    if (!myUid) {
      throw new functions.https.HttpsError(
        "unauthenticated",
        "User must be logged in"
      );
    }

    if (!friendUid) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "friendUid is required"
      );
    }

    const db = admin.firestore();

    const myRef = db
      .collection("users")
      .document(myUid)
      .collection("friends")
      .document(friendUid);

    const friendRef = db
      .collection("users")
      .document(friendUid)
      .collection("friends")
      .document(myUid);

    // Transaction for atomicity
    await db.runTransaction(async (tx) => {
      tx.delete(myRef);
      tx.delete(friendRef);
    });

    return { success: true };
  }
);
