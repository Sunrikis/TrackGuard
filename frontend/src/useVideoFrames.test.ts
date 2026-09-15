import { afterEach, expect, test, vi } from 'vitest'
import { api, type VideoFrame } from './api'
import { useVideoFrames } from './useVideoFrames'
vi.mock('./api', () => ({ api: { createPreview: vi.fn(), previewFrame: vi.fn(), sourceFrame: vi.fn(), removePreview: vi.fn().mockResolvedValue(undefined) }, messageOf: (e: Error) => e.message }))
const frame = (time: number): VideoFrame => ({ image: `frame-${time}`, time, duration: 4 })
const event = (time: number) => ({ target: { value: String(time) } }) as unknown as Event
afterEach(() => { vi.useRealTimers(); vi.clearAllMocks() })

test('rapid dragging ignores stale frames and loads the final position', async () => {
  vi.useFakeTimers()
  vi.mocked(api.sourceFrame).mockResolvedValueOnce(frame(0))
  const viewer = useVideoFrames()
  await viewer.open('task')
  let finish!: (value: VideoFrame) => void
  vi.mocked(api.sourceFrame).mockImplementationOnce(() => new Promise((resolve) => { finish = resolve }))
  viewer.seek(event(1))
  await vi.advanceTimersByTimeAsync(200)
  viewer.seek(event(3))
  vi.mocked(api.sourceFrame).mockResolvedValueOnce(frame(3))
  finish(frame(1))
  await vi.advanceTimersByTimeAsync(200)
  expect(viewer.image.value).toBe('frame-3')
  expect(viewer.time.value).toBe(3)
  expect(viewer.loading.value).toBe(false)
  viewer.reset()
})

test('removing a file during upload discards the response and cleans its temporary preview', async () => {
  let finish!: (value: VideoFrame) => void
  vi.mocked(api.createPreview).mockImplementationOnce(() => new Promise((resolve) => { finish = resolve }))
  const viewer = useVideoFrames()
  const opening = viewer.open({ name: 'test.mp4' } as File)
  viewer.reset()
  finish({ ...frame(0), id: 'abandoned' })
  await opening
  expect(viewer.image.value).toBe('')
  expect(api.removePreview).toHaveBeenCalledWith('abandoned')
})

test('a frame read error is visible and can be retried', async () => {
  vi.mocked(api.sourceFrame).mockRejectedValueOnce(new Error('解码失败'))
  const viewer = useVideoFrames()
  await viewer.open('task')
  expect(viewer.error.value).toBe('解码失败')
  vi.mocked(api.sourceFrame).mockResolvedValueOnce(frame(0))
  await viewer.open('task')
  expect(viewer.error.value).toBe('')
  expect(viewer.image.value).toBe('frame-0')
  viewer.reset()
})
