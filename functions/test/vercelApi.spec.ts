import { expect } from "chai";
import handler from "../api/friend-place-added";

describe("Vercel Serverless API /api/friend-place-added Tests", () => {
  it("exports a valid Vercel request handler function", () => {
    expect(handler).to.be.a("function");
  });

  it("handles Method Not Allowed for non-POST requests", async () => {
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

  it("handles Unauthorized for missing or invalid Authorization header", async () => {
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

  it("handles Unauthorized for invalid Bearer token", async () => {
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
});
