export interface PlaceDocument {
  kakaoPlaceId: string;
  kakaoPlaceUrl: string;
  searchKeyword: string;
  searchRegion?: string | null;
  matchType: "auto" | "confirmed" | "manual";
  sourceType: "screenshot" | "link" | "text";
  addedBy: string;
  addedByNickname: string;
  likedBy: string[];
  likeCount: number;
  notificationBatchId?: string | null;
  createdAt: any;
}

export interface RoomDocument {
  name: string;
  region: string;
  ownerId: string;
  memberIds: string[];
  members: Record<string, { nickname: string; avatarColor: string; lastSeenAt?: any }>;
  placeCount: number;
  createdAt: any;
  updatedAt: any;
}

export interface DeviceTokenDocument {
  token: string;
  platform: "android" | string;
  updatedAt: any;
  installationId?: string;
}

export interface RecipientDeviceTarget {
  recipientUid: string;
  installationId: string;
  token: string;
  deliveryId: string;
}

export interface DeliveryRecord {
  deliveryId: string;
  recipientUid: string;
  installationId: string;
  status: "pending" | "in_progress" | "success" | "failed_transient" | "failed_permanent";
  leaseOwner?: string | null;
  leaseExpiresAt?: number | null;
  retryCount?: number;
  errorCode?: string | null;
  updatedAt: any;
}

export interface NotificationEventRecord {
  eventId: string;
  roomId: string;
  kakaoPlaceId: string;
  addedBy: string;
  status: "pending" | "in_progress" | "completed" | "failed_transient" | "failed_permanent";
  createdAt: any;
  updatedAt: any;
}
