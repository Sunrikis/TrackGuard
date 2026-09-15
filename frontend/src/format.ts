import type { Category, TaskStatus, ReviewStatus, Point } from './types'
export const categoryLabels: Record<Category, string> = {
  person: '人员',
  vehicle: '车辆',
  motorcycle: '摩托车',
  animal: '动物',
  obstacle: '岩石 / 障碍物',
}
export const taskLabels: Record<TaskStatus, string> = {
  QUEUED: '排队中',
  RUNNING: '检测中',
  SUCCEEDED: '已完成',
  FAILED: '检测失败',
  CANCELLED: '已取消',
}
export const reviewLabels: Record<ReviewStatus, string> = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  RESOLVED: '已处理',
  FALSE_POSITIVE: '已标记误报',
}
export const categoryColors: Record<Category, string> = {
  person: '#5471f8',
  vehicle: '#f2ad55',
  motorcycle: '#e27791',
  animal: '#4dbea4',
  obstacle: '#9e88db',
}
export function dateTime(value?: string) {
  return value
    ? new Intl.DateTimeFormat('zh-CN', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
        timeZone: 'Asia/Shanghai',
      }).format(new Date(value))
    : '—'
}
export function duration(ms?: number) {
  return ms == null ? '—' : ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} s`
}
export const isActive = (status: TaskStatus) => status === 'QUEUED' || status === 'RUNNING'
function orientation(a: Point, b: Point, c: Point) {
  return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
}
function segmentsIntersect(a: Point, b: Point, c: Point, d: Point) {
  const epsilon = 1e-7
  const values = [orientation(a, b, c), orientation(a, b, d), orientation(c, d, a), orientation(c, d, b)]
  const within = (p: Point, x: Point, y: Point) =>
    p[0] >= Math.min(x[0], y[0]) - epsilon &&
    p[0] <= Math.max(x[0], y[0]) + epsilon &&
    p[1] >= Math.min(x[1], y[1]) - epsilon &&
    p[1] <= Math.max(x[1], y[1]) + epsilon
  if (Math.abs(values[0]) <= epsilon && within(c, a, b)) return true
  if (Math.abs(values[1]) <= epsilon && within(d, a, b)) return true
  if (Math.abs(values[2]) <= epsilon && within(a, c, d)) return true
  if (Math.abs(values[3]) <= epsilon && within(b, c, d)) return true
  return values[0] * values[1] < 0 && values[2] * values[3] < 0
}
export function validRegion(points: Point[]) {
  if (
    points.length < 4 ||
    points.length > 24 ||
    points.some((p) => p.some((n) => !Number.isFinite(n) || n < 0 || n > 1))
  )
    return false
  let area = 0
  for (let i = 0; i < points.length; i++) {
    const p = points[i],
      q = points[(i + 1) % points.length]
    if (Math.hypot(p[0] - q[0], p[1] - q[1]) < 0.00001) return false
    area += p[0] * q[1] - q[0] * p[1]
    for (let j = i + 1; j < points.length; j++) {
      if (j === i + 1 || (i === 0 && j === points.length - 1)) continue
      if (segmentsIntersect(p, q, points[j], points[(j + 1) % points.length])) return false
    }
  }
  return Math.abs(area) / 2 >= 0.005
}
