import type { VercelRequest, VercelResponse } from "@vercel/node";
import handler from "../functions/api/friend-place-added";

export default function (req: VercelRequest, res: VercelResponse) {
  return handler(req, res);
}
