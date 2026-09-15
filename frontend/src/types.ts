export type Page = 'overview' | 'detect' | 'records' | 'statistics'
export type Category = 'person' | 'vehicle' | 'motorcycle' | 'animal' | 'obstacle'
export type TaskStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type ReviewStatus = 'PENDING' | 'PROCESSING' | 'RESOLVED' | 'FALSE_POSITIVE'
export type Point = [number, number]
export interface UserProfile {
  id: string
  username: string
  displayName: string
  avatarUrl?: string
}
export interface SessionView {
  user?: UserProfile
  csrfToken: string
}
export interface DetectionModel {
  id: string
  name: string
  description: string
  categories: Category[]
  available: boolean
  isDefault: boolean
}
export interface DetectionTimings {
  startupMs: number
  modelLoadMs: number
  decodeMs: number
  regionMs: number
  inferenceMs: number
  postprocessMs: number
  otherMs: number
  processOverheadMs: number
  workerMs: number
  totalMs: number
  workerWaitMs?: number
  modelHashMs?: number
  associationMs?: number
  annotationMs?: number
  snapshotMs?: number
  progressMs?: number
  regionComputeMs?: number
  adviceMs?: number
}
export interface DetectionPerformance {
  inferenceFps: number
  pipelineFps: number
  taskFps?: number
  batchSize: number
  imageSize: number
  precision: 'FP16' | 'FP32'
  savedSnapshots: number
  sourceFrames: number
  sourceFps: number
  parallelRegions?: boolean
  regionFallbackFrames?: number
  samplingMode: 'ALL_FRAMES' | 'SAMPLED' | 'IMAGE'
}
export interface Detection {
  trackKey: string
  category: Category
  label: string
  confidence: number
  box: number[]
  inDanger: boolean
  risk: string
  frameTime: number
  snapshot: string
  advice: string
  adviceSource?: 'LOCAL_RULE' | 'DEEPSEEK'
}
export interface Result {
  detections: Detection[]
  region: Point[]
  regionSource: string
  preview: string
  sampledFrames: number
  durationSeconds: number
  inferenceMs: number
  model: string
  device?: 'CUDA' | 'CPU'
  workerMode?: 'PERSISTENT' | 'ONESHOT'
  workerPid?: number
  modelReused?: boolean
  timings?: DetectionTimings
  performance?: DetectionPerformance
  objectCount: number
  warningCount: number
  outsideCount: number
  capabilities: Category[]
  visionAdvice?: {
    configured: boolean
    model: string
    scope: 'VIDEO' | 'IMAGE'
    requestedSnapshots: number
    generatedSnapshots: number
    failedSnapshots: number
    skippedSnapshots: number
    overallAdvice: string
    errorMessage?: string
  }
}
export interface TaskLog {
  level: string
  message: string
  createdAt: string
}
export interface Task {
  id: string
  title: string
  sourceName: string
  mediaType: 'IMAGE' | 'VIDEO'
  status: TaskStatus
  progress: number
  confidence: number
  sampleSeconds: number
  modelId: string
  modelName: string
  targetCategories: Category[]
  createdAt: string
  finishedAt?: string
  elapsedMs?: number
  eventCount: number
  attempt: number
  errorMessage?: string
  result?: Result
  roi?: Point[]
  logs?: TaskLog[]
}
export interface WarningEvent {
  id: string
  taskId: string
  taskTitle: string
  modelName: string
  category: Category
  label: string
  risk: string
  confidence: number
  frameTime: number
  snapshot: string
  status: ReviewStatus
  reviewNote?: string
  reviewedAt?: string
  createdAt: string
  advice: string
  adviceSource: 'LOCAL_RULE' | 'DEEPSEEK'
}
export interface Statistics {
  totalTasks: number
  completedTasks: number
  failedTasks: number
  avgElapsedMs: number
  allEvents: number
  warningCount: number
  resolvedCount: number
  falsePositiveCount: number
  pendingCount: number
  categories: { category: Category; count: number }[]
  trend: { date: string; count: number }[]
  days: number
  today: string
}
export interface Health {
  status: string
  database: string
  modelReady: boolean
}
