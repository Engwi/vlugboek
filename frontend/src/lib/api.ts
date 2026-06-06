import { Capacitor, registerPlugin } from '@capacitor/core';
import type { AuthResponse, DashboardDto, DatasetDto, DocumentDto, LabelDto, LeaderboardDto, OrganisationTreeDto, UploadResponse, UserAdminDto } from './types';

const API_BASE = import.meta.env.VITE_API_URL ?? '';
const AUTH_STORAGE_KEY = 'vlugboek.user';

export type ReportFilters = {
  query?: string;
  family?: string;
  category?: string;
  dateFrom?: string;
  dateTo?: string;
  federationId?: string;
  clubId?: string;
  loftId?: string;
  racePoint?: string;
};

type NativeDownloadsPlugin = {
  saveAndOpen(options: {
    filename: string;
    mimeType: string;
    base64: string;
    open?: boolean;
  }): Promise<{ uri: string }>;
};

const NativeDownloads = registerPlugin<NativeDownloadsPlugin>('VlugboekDownloads');

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly path: string
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export function readStoredAuth(): AuthResponse | null {
  const stored = localStorage.getItem(AUTH_STORAGE_KEY);
  if (!stored) return null;

  try {
    const user = JSON.parse(stored) as Partial<AuthResponse>;
    if (
      typeof user.token === 'string' &&
      user.token.trim() &&
      typeof user.email === 'string' &&
      typeof user.displayName === 'string' &&
      (user.role === 'USER' || user.role === 'ADMIN' || user.role === 'SYSTEM_ADMIN' || user.role === 'FEDERATION_ADMIN')
    ) {
      return user as AuthResponse;
    }
  } catch {
    // Fall through and clear malformed legacy data below.
  }

  clearStoredAuth();
  return null;
}

export function writeStoredAuth(user: AuthResponse) {
  localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(user));
}

export function clearStoredAuth() {
  localStorage.removeItem(AUTH_STORAGE_KEY);
}

export function isUnauthorized(error: unknown) {
  return error instanceof ApiError && error.status === 401;
}

function authHeaders(): Record<string, string> {
  const user = readStoredAuth();
  const headers: Record<string, string> = {};
  if (user?.token) {
    headers.Authorization = `Bearer ${user.token}`;
  }
  if (user?.language) {
    headers['Accept-Language'] = user.language;
  }
  return headers;
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders(),
      ...(options?.headers ?? {})
    },
    ...options
  });

  if (!response.ok) {
    throw await toApiError(response, path);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}

export const api = {
  dashboard: () => request<DashboardDto>('/api/dashboard'),
  reports: (filters: ReportFilters = {}) => {
    const params = new URLSearchParams();
    Object.entries(filters).forEach(([key, value]) => {
      if (value) params.set(key, value);
    });
    return request<DocumentDto[]>(`/api/reports?${params.toString()}`);
  },
  report: (id: number) => request<DatasetDto>(`/api/reports/${id}`),
  documents: () => request<DocumentDto[]>('/api/documents'),
  leaderboards: () => request<LeaderboardDto[]>('/api/leaderboards'),
  races: () => request<DocumentDto[]>('/api/races'),
  federations: () => request<LabelDto[]>('/api/federations'),
  clubs: (federationId: number) => request<LabelDto[]>(`/api/clubs?federationId=${federationId}`),
  lofts: (clubId: number) => request<LabelDto[]>(`/api/lofts?clubId=${clubId}`),
  organisationTree: () => request<OrganisationTreeDto>('/api/admin/organisations'),
  createFederation: (payload: { code: string; name: string }) => request('/api/admin/federations', {
    method: 'POST',
    body: JSON.stringify(payload)
  }),
  updateFederation: (id: number, payload: { code: string; name: string }) => request(`/api/admin/federations/${id}`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  }),
  deleteFederation: (id: number) => request<void>(`/api/admin/federations/${id}`, { method: 'DELETE' }),
  setFederationAdmin: (id: number, payload: { email: string }) => request(`/api/admin/federations/${id}/admin`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  }),
  createClub: (payload: { federationId: number; name: string }) => request('/api/admin/clubs', {
    method: 'POST',
    body: JSON.stringify(payload)
  }),
  updateClub: (id: number, payload: { name: string }) => request(`/api/admin/clubs/${id}`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  }),
  deleteClub: (id: number) => request<void>(`/api/admin/clubs/${id}`, { method: 'DELETE' }),
  createLoft: (payload: { clubId: number; name: string }) => request('/api/admin/lofts', {
    method: 'POST',
    body: JSON.stringify(payload)
  }),
  updateLoft: (id: number, payload: { name: string }) => request(`/api/admin/lofts/${id}`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  }),
  deleteLoft: (id: number) => request<void>(`/api/admin/lofts/${id}`, { method: 'DELETE' }),
  preloadUser: (payload: { email: string; federationId: number; clubId: number; loftId: number }) => request<UserAdminDto>('/api/admin/preloaded-users', {
    method: 'POST',
    body: JSON.stringify(payload)
  }),
  login: (payload: Record<string, unknown>) => request<AuthResponse>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify(payload)
  }),
  register: (payload: Record<string, unknown>) => request<AuthResponse>('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify(payload)
  }),
  requestPasswordReset: (payload: { email: string; language: string }) => request<{ message: string }>('/api/auth/password-reset/request', {
    method: 'POST',
    body: JSON.stringify(payload)
  }),
  confirmPasswordReset: (payload: { email: string; token: string; password: string; language: string }) => request<AuthResponse>('/api/auth/password-reset/confirm', {
    method: 'POST',
    body: JSON.stringify(payload)
  }),
  updateLanguage: (language: string) => request<AuthResponse>('/api/profile/language', {
    method: 'POST',
    body: JSON.stringify({ language })
  }),
  changePassword: (payload: { currentPassword: string; newPassword: string }) => request<AuthResponse>('/api/profile/password', {
    method: 'POST',
    body: JSON.stringify(payload)
  }),
  upload: async (file: File) => {
    const form = new FormData();
    form.append('file', file);
    const response = await fetch(`${API_BASE}/api/documents/upload`, {
      method: 'POST',
      headers: authHeaders(),
      body: form
    });
    if (!response.ok) {
      throw await toApiError(response, '/api/documents/upload');
    }
    return response.json() as Promise<UploadResponse>;
  },
  confirmImport: (id: number) => request<UploadResponse>(`/api/documents/${id}/confirm`, {
    method: 'POST'
  }),
  emailDocument: (id: number) => request<{ status: string; message: string; messageId?: string; deliveryId?: string; requestId?: string }>(`/api/documents/${id}/email`, {
    method: 'POST'
  })
};

export async function openAsset(path: string, filename = 'vlugboek-document.pdf') {
  const blob = await fetchAsset(path);
  if (Capacitor.isNativePlatform()) {
    await saveNativeAsset(blob, filename, true);
    return;
  }

  const url = URL.createObjectURL(blob);
  window.open(url, '_blank', 'noopener');
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

export async function downloadAsset(path: string, filename: string) {
  const blob = await fetchAsset(path);
  if (Capacitor.isNativePlatform()) {
    await saveNativeAsset(blob, filename, true);
    return;
  }

  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

async function fetchAsset(path: string) {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: authHeaders()
  });
  if (!response.ok) {
    throw await toApiError(response, path);
  }
  return response.blob();
}

async function saveNativeAsset(blob: Blob, filename: string, open: boolean) {
  await NativeDownloads.saveAndOpen({
    filename,
    mimeType: normaliseMimeType(blob.type || mimeTypeFor(filename)),
    base64: await blobToBase64(blob),
    open
  });
}

function blobToBase64(blob: Blob) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = String(reader.result ?? '');
      resolve(result.includes(',') ? result.split(',')[1] : result);
    };
    reader.onerror = () => reject(reader.error ?? new Error('Could not read downloaded file'));
    reader.readAsDataURL(blob);
  });
}

function mimeTypeFor(filename: string) {
  if (filename.toLowerCase().endsWith('.pdf')) return 'application/pdf';
  if (filename.toLowerCase().endsWith('.csv')) return 'text/csv';
  return 'application/octet-stream';
}

function normaliseMimeType(mimeType: string) {
  return mimeType.split(';')[0].trim() || 'application/octet-stream';
}

async function toApiError(response: Response, path: string) {
  const body = await response.json().catch(() => ({ message: response.statusText }));
  return new ApiError(body.message ?? 'Request failed', response.status, path);
}
