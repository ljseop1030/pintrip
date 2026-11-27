import { expect } from "chai";
import * as admin from "firebase-admin";
import handler from "../api/friend-place-added";
import { RoomDocument } from "../src/types";

describe("Vercel Serverless API /api/friend-place-added Tests", () => {
  let originalAuthDescriptor: PropertyDescriptor | undefined;
  let originalFirestoreDescriptor: PropertyDescriptor | undefined;

  before(() => {
    if (admin.apps.length === 0) {
      admin.initializeApp();
    }
    originalAuthDescriptor = Object.getOwnPropertyDescriptor(admin, "auth");
    originalFirestoreDescriptor = Object.getOwnPropertyDescriptor(admin, "firestore");

    Object.defineProperty(admin, "auth", {
      get: () => () => ({
        verifyIdToken: async (token: string) => {
          if (token === "valid_author_token") {
            return { uid: "user_author" };
          }
          if (token === "valid_stranger_token") {
            return { uid: "user_stranger" };
          }
          if (token === "valid_non_author_token") {
            return { uid: "user_member2" };
          }
          throw new Error("Invalid token");
        },
      }),
      configurable: true,
    });
  });

  after(() => {
    if (originalAuthDescriptor) {
      Object.defineProperty(admin, "auth", originalAuthDescriptor);
    }
    if (originalFirestoreDescriptor) {
      Object.defineProperty(admin, "firestore", originalFirestoreDescriptor);
    }
  });

  it("exports a valid Vercel request handler function", () => {
    expect(handler).to.be.a("function");
  });

  it("handles Method Not Allowed for non-POST requests (HTTP 405)", async () => {
    let statusCode = 0;
    let jsonResult: any = null;

    const req: any = {
      method: "GET",
      headers: {},
      body: {},
    };

    const res: any = {
      status: (code: number) => {
        statusCode = code;
        return {
          json: (data: any) => {
            jsonResult = data;
          },
        };
      },
    };

    await handler(req, res);
    expect(statusCode).to.equal(405);
    expect(jsonResult.error).to.include("Method Not Allowed");
  });

  it("handles Unauthorized for missing Authorization header (HTTP 401)", async () => {
    let statusCode = 0;
    let jsonResult: any = null;

    const req: any = {
      method: "POST",
      headers: {},
      body: { roomId: "room_1", notificationBatchId: "batch_123" },
    };

    const res: any = {
      status: (code: number) => {
        statusCode = code;
        return {
          json: (data: any) => {
            jsonResult = data;
          },
        };
      },
    };

    await handler(req, res);
    expect(statusCode).to.equal(401);
    expect(jsonResult.error).to.include("Unauthorized");
  });

  it("handles Unauthorized for invalid Bearer token (HTTP 401)", async () => {
    let statusCode = 0;
    let jsonResult: any = null;

    const req: any = {
      method: "POST",
      headers: { authorization: "Bearer invalid_mock_token_123" },
      body: { roomId: "room_1", notificationBatchId: "batch_123" },
    };

    const res: any = {
      status: (code: number) => {
        statusCode = code;
        return {
          json: (data: any) => {
            jsonResult = data;
          },
        };
      },
    };

    await handler(req, res);
    expect(statusCode).to.equal(401);
    expect(jsonResult.error).to.include("Unauthorized");
  });

  it("handles Bad Request for missing roomId after valid auth (HTTP 400)", async () => {
    let statusCode = 0;
    let jsonResult: any = null;

    const req: any = {
      method: "POST",
      headers: { authorization: "Bearer valid_author_token" },
      body: { notificationBatchId: "batch_123" }, // missing roomId
    };

    const res: any = {
      status: (code: number) => {
        statusCode = code;
        return {
          json: (data: any) => {
            jsonResult = data;
          },
        };
      },
    };

    await handler(req, res);
    expect(statusCode).to.equal(400);
    expect(jsonResult.error).to.include("Bad Request");
  });

  it("handles Forbidden for non-room-member after valid auth (HTTP 403)", async () => {
    let statusCode = 0;
    let jsonResult: any = null;

    const mockFirestore = () => ({
      collection: (col: string) => ({
        doc: (docId: string) => ({
          get: async () => {
            if (col === "rooms" && docId === "room_secret") {
              return {
                exists: true,
                data: (): RoomDocument => ({
                  name: "Secret Room",
                  region: "Seoul",
                  ownerId: "user_author",
                  memberIds: ["user_author", "user_member2"], // user_stranger is NOT a member
                  members: {},
                  placeCount: 1,
                  createdAt: null,
                  updatedAt: null,
                }),
              };
            }
            return { exists: false };
          },
        }),
      }),
    });

    Object.defineProperty(admin, "firestore", {
      get: () => mockFirestore,
      configurable: true,
    });

    try {
      const req: any = {
        method: "POST",
        headers: { authorization: "Bearer valid_stranger_token" },
        body: { roomId: "room_secret", notificationBatchId: "batch_123" },
      };

      const res: any = {
        status: (code: number) => {
          statusCode = code;
          return {
            json: (data: any) => {
              jsonResult = data;
            },
          };
        },
      };

      await handler(req, res);
      expect(statusCode).to.equal(403);
      expect(jsonResult.error).to.include("Forbidden");
    } finally {
      if (originalFirestoreDescriptor) {
        Object.defineProperty(admin, "firestore", originalFirestoreDescriptor);
      }
    }
  });

  it("handles Forbidden when room member is NOT the batch author (HTTP 403)", async () => {
    let statusCode = 0;
    let jsonResult: any = null;

    const mockFirestore = () => ({
      collection: (col: string) => ({
        doc: (docId: string) => ({
          get: async () => ({
            exists: true,
            data: (): RoomDocument => ({
              name: "Public Room",
              region: "Seoul",
              ownerId: "user_author",
              memberIds: ["user_author", "user_member2"], // user_member2 is member
              members: {},
              placeCount: 1,
              createdAt: null,
              updatedAt: null,
            }),
          }),
          collection: (subCol: string) => ({
            where: (field: string, op: string, val: any) => ({
              get: async () => ({
                empty: false,
                docs: [
                  {
                    id: "place_1",
                    data: () => ({
                      addedBy: "user_author", // Author is user_author, NOT user_member2
                      notificationBatchId: "batch_author_123",
                    }),
                  },
                ],
              }),
            }),
          }),
        }),
      }),
    });

    Object.defineProperty(admin, "firestore", {
      get: () => mockFirestore,
      configurable: true,
    });

    try {
      const req: any = {
        method: "POST",
        headers: { authorization: "Bearer valid_non_author_token" }, // user_member2
        body: { roomId: "room_public", notificationBatchId: "batch_author_123" },
      };

      const res: any = {
        status: (code: number) => {
          statusCode = code;
          return {
            json: (data: any) => {
              jsonResult = data;
            },
          };
        },
      };

      await handler(req, res);
      expect(statusCode).to.equal(403);
      expect(jsonResult.error).to.include("Forbidden");
    } finally {
      if (originalFirestoreDescriptor) {
        Object.defineProperty(admin, "firestore", originalFirestoreDescriptor);
      }
    }
  });
});
