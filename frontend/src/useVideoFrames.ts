import { ref } from 'vue'
import { api, messageOf, type VideoFrame } from './api'

/** Keep only the latest requested frame; never decode video in the browser. */
export function useVideoFrames() {
  const image = ref(''), duration = ref(0), time = ref(0), loading = ref(false), error = ref('')
  let generation = 0, revision = 0, previewId = '', taskId = '', busy = false
  let timer: ReturnType<typeof setTimeout> | undefined
  function reset() {
    generation++
    revision++
    clearTimeout(timer)
    if (previewId) void api.removePreview(previewId).catch(() => {})
    previewId = taskId = ''
    busy = false
    image.value = error.value = ''
    duration.value = time.value = 0
    loading.value = false
  }
  function display(frame: VideoFrame) {
    image.value = frame.image
    duration.value = frame.duration
    time.value = frame.time
    error.value = ''
  }
  async function open(source: File | string) {
    reset()
    const current = generation
    loading.value = true
    try {
      const frame = typeof source === 'string' ? await api.sourceFrame(source, 0) : await api.createPreview(source)
      if (current !== generation) {
        if (frame.id) void api.removePreview(frame.id).catch(() => {})
        return
      }
      if (typeof source === 'string') taskId = source
      else previewId = frame.id || ''
      display(frame)
    } catch (e) {
      if (current === generation) error.value = messageOf(e)
    } finally {
      if (current === generation) loading.value = false
    }
  }
  async function fetchFrame() {
    if (busy || (!previewId && !taskId)) return
    clearTimeout(timer)
    const current = generation, requested = revision
    busy = true
    loading.value = true
    try {
      const frame = taskId ? await api.sourceFrame(taskId, time.value) : await api.previewFrame(previewId, time.value)
      if (current === generation && requested === revision) display(frame)
    } catch (e) {
      if (current === generation && requested === revision) error.value = messageOf(e)
    } finally {
      if (current === generation) {
        busy = false
        if (requested !== revision) void fetchFrame()
        else loading.value = false
      }
    }
  }
  function seek(event: Event) {
    time.value = Number((event.target as HTMLInputElement).value)
    revision++
    loading.value = true
    error.value = ''
    clearTimeout(timer)
    timer = setTimeout(() => void fetchFrame(), 180)
  }
  return { image, duration, time, loading, error, open, seek, reset }
}
