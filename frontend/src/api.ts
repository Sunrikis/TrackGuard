import axios from 'axios'
import type {
  Task,
  WarningEvent,
  Statistics,
  Health,
  ReviewStatus,
  Point,
  Category,
  DetectionModel,
  SessionView,
  UserProfile,
} from './types'
const http = axios.create({ baseURL: '/api', timeout: 15000 })
export interface VideoFrame { image: string; duration: number; time: number; id?: string }
let csrfToken = ''
http.interceptors.request.use((config) => {
  if (!['get', 'head', 'options'].includes(config.method || 'get'))
    config.headers.set('X-CSRF-Token', csrfToken)
  return config
})
http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.data?.code === 'LOGIN_REQUIRED')
      window.dispatchEvent(new Event('session-expired'))
    return Promise.reject(error)
  },
)
function rememberSession(session: SessionView) {
  csrfToken = session.csrfToken
  return session
}
export function messageOf(error: unknown): string {
  if (axios.isAxiosError(error))
    return (
      error.response?.data?.message ||
      (error.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : '无法连接服务，请确认后端已启动')
    )
  return error instanceof Error ? error.message : '操作失败，请重试'
}
export const api = {
  createPreview: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<VideoFrame>('/video-previews', form, { timeout: 120000 }).then((r) => r.data)
  },
  previewFrame: (id: string, time: number) =>
    http.get<VideoFrame>(`/video-previews/${id}`, { params: { time }, timeout: 40000 }).then((r) => r.data),
  removePreview: (id: string) => http.delete(`/video-previews/${id}`),
  sourceFrame: (id: string, time: number) =>
    http.get<VideoFrame>(`/tasks/${id}/source-frame`, { params: { time }, timeout: 40000 }).then((r) => r.data),
  session: () => http.get<SessionView>('/auth/session').then((r) => rememberSession(r.data)),
  login: (username: string, password: string) =>
    http
      .post<SessionView>('/auth/login', { username, password })
      .then((r) => rememberSession(r.data)),
  register: (username: string, password: string, displayName: string) =>
    http
      .post<SessionView>('/auth/register', { username, password, displayName })
      .then((r) => rememberSession(r.data)),
  logout: () =>
    http.post('/auth/logout').then(() => {
      csrfToken = ''
    }),
  avatar: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<UserProfile>('/auth/avatar', form).then((r) => r.data)
  },
  models: () => http.get<DetectionModel[]>('/models').then((r) => r.data),
  health: () => http.get<Health>('/health').then((r) => r.data),
  stats: (days = 7) =>
    http.get<Statistics>('/statistics', { params: { days } }).then((r) => r.data),
  tasks: (q = '', status = '', page = 1, size = 12, sort = 'createdAt', direction = 'desc') =>
    http
      .get<{ items: Task[]; total: number }>('/tasks', { params: { q, status, page, size, sort, direction } })
      .then((r) => r.data),
  task: (id: string) => http.get<Task>(`/tasks/${id}`).then((r) => r.data),
  eventPage: (category = '', status = '', page = 1, size = 20, sort = 'createdAt', direction = 'desc') =>
    http.get<{ items: WarningEvent[]; total: number }>('/events/page', { params: { category, status, page, size, sort, direction } }).then((r) => r.data),
  events: (taskId = '', category = '', status = '') =>
    http
      .get<WarningEvent[]>('/events', { params: { taskId, category, status } })
      .then((r) => r.data),
  cancel: (id: string) => http.post<Task>(`/tasks/${id}/cancel`).then((r) => r.data),
  retry: (id: string) => http.post<Task>(`/tasks/${id}/retry`).then((r) => r.data),
  deleteTask: (id: string) => http.delete(`/tasks/${id}`),
  deleteEvent: (id: string) => http.delete(`/events/${id}`),
  deleteTasks: (ids: string[]) => http.post<{ deletedCount: number }>('/tasks/batch-delete', { ids }, { timeout: 60000 }).then((r) => r.data),
  deleteEvents: (ids: string[]) => http.post<{ deletedCount: number }>('/events/batch-delete', { ids }, { timeout: 60000 }).then((r) => r.data),
  review: (id: string, status: ReviewStatus, note: string) =>
    http.patch(`/events/${id}`, { status, note }),
  upload: (
    file: File,
    title: string,
    confidence: number,
    sampleSeconds: number,
    roi: Point[],
    modelId: string,
    targetCategories: Category[],
    progress: (n: number) => void,
  ) => {
    const form = new FormData()
    form.append('file', file)
    form.append('title', title)
    form.append('confidence', String(confidence))
    form.append('sampleSeconds', String(sampleSeconds))
    form.append('roi', JSON.stringify(roi))
    form.append('modelId', modelId)
    form.append('targetCategories', JSON.stringify(targetCategories))
    return http
      .post<Task>('/tasks', form, {
        timeout: 120000,
        onUploadProgress: (e) => progress(Math.round((e.loaded / (e.total || file.size)) * 100)),
      })
      .then((r) => r.data)
  },
}
export const sourceUrl = (id: string) => `/api/tasks/${id}/source`
export const assetUrl = (id: string, name: string) =>
  `/api/tasks/${id}/assets/${encodeURIComponent(name)}`
export async function downloadReport(id: string, kind: 'report' | 'events.csv' = 'report') {
  const response = await http.get(`/tasks/${id}/${kind}`, { responseType: 'blob', timeout: 30000 })
  const url = URL.createObjectURL(response.data)
  const a = document.createElement('a')
  a.href = url
  a.download = `${kind === 'report' ? '巡检报告' : '预警记录'}-${id.slice(0, 8)}.${kind === 'report' ? 'html' : 'csv'}`
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 5000)
}
