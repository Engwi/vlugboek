export type LabelDto = {
  id: number;
  name: string;
  code?: string | null;
};

export type OrganisationTreeDto = {
  federations: FederationAdminDto[];
};

export type FederationAdminDto = {
  id: number;
  code: string;
  name: string;
  country: string;
  userCount: number;
  documentCount: number;
  clubCount: number;
  locked: boolean;
  federationAdmin?: UserAdminDto | null;
  clubs: ClubAdminDto[];
};

export type ClubAdminDto = {
  id: number;
  federationId: number;
  name: string;
  userCount: number;
  documentCount: number;
  loftCount: number;
  locked: boolean;
  lofts: LoftAdminDto[];
};

export type LoftAdminDto = {
  id: number;
  clubId: number;
  name: string;
  userCount: number;
  documentCount: number;
  locked: boolean;
};

export type AuthResponse = {
  token: string;
  email: string;
  displayName: string;
  role: 'USER' | 'FEDERATION_ADMIN' | 'SYSTEM_ADMIN' | 'ADMIN';
  language: string;
  federation?: LabelDto | null;
  club?: LabelDto | null;
  loft?: LabelDto | null;
};

export type UserAdminDto = {
  id: number;
  email: string;
  displayName: string;
  role: 'USER' | 'FEDERATION_ADMIN' | 'SYSTEM_ADMIN' | 'ADMIN';
  registered: boolean;
  federation?: LabelDto | null;
  club?: LabelDto | null;
  loft?: LabelDto | null;
};

export type DocumentDto = {
  id: number;
  title: string;
  originalFilename: string;
  reportFamily: 'DISTANCE_LOG' | 'RACE_DETAIL' | 'CLASSIFICATION' | 'COMBINE' | 'UNKNOWN';
  classificationCategory: string;
  status: string;
  recognisedType: string;
  federation?: LabelDto | null;
  racePoint?: string | null;
  clubNames: string[];
  loftNames: string[];
  officialDate?: string | null;
  liberatedAt?: string | null;
  reportCreatedAt?: string | null;
  fileSize: number;
  availableToUsers: boolean;
  uploadedAt: string;
  pdfUrl: string;
  csvUrl: string;
};

export type DatasetDto = {
  document: DocumentDto;
  title: string;
  columns: string[];
  rows: string[][];
};

export type UploadResponse = {
  message: string;
  document: DocumentDto;
  dataset: DatasetDto;
};

export type IngestionItemDto = {
  id: number;
  status: string;
  filename: string;
  sourcePath?: string | null;
  archivePath?: string | null;
  contentSha256?: string | null;
  fileSize?: number | null;
  documentId?: number | null;
  title?: string | null;
  recognisedType?: string | null;
  reportFamily?: string | null;
  rowCount?: number | null;
  columnCount?: number | null;
  message?: string | null;
  warnings?: string | null;
};

export type IngestionRunDto = {
  id: number;
  status: string;
  startedByEmail?: string | null;
  startedAt: string;
  completedAt?: string | null;
  inboxPath: string;
  reportPath?: string | null;
  reportUrl: string;
  totalFiles: number;
  importedCount: number;
  suspectCount: number;
  duplicateCount: number;
  rejectedCount: number;
  failedCount: number;
  items: IngestionItemDto[];
};

export type IngestionWorkspaceDto = {
  rootPath: string;
  inboxPath: string;
  processingPath: string;
  importedPath: string;
  skippedPath: string;
  rejectedPath: string;
  reportsPath: string;
  runs: IngestionRunDto[];
};

export type LeaderboardDto = {
  category: string;
  title: string;
  snapshotDate: string;
  columns: string[];
  rows: string[][];
};

export type DashboardDto = {
  documentCount: number;
  raceCount: number;
  leaderboardCount: number;
  federationCount: number;
  recentDocuments: DocumentDto[];
};
