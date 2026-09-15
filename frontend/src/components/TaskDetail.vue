<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  ArrowDownToLine,
  Download,
  FileSearch,
  LoaderCircle,
  RotateCcw,
  ShieldCheck,
  Square,
  TriangleAlert,
  X,
} from 'lucide-vue-next'
import { api, assetUrl, sourceUrl, downloadReport, messageOf } from '../api'
import { dateTime, duration, isActive } from '../format'
import type { Task, WarningEvent } from '../types'
import StatusBadge from './StatusBadge.vue'
import ReviewDialog from './ReviewDialog.vue'
import VideoFramePreview from './VideoFramePreview.vue'
const props = defineProps<{ id: string }>()
const emit = defineEmits<{ close: []; changed: []; notify: [message: string] }>()
const task = ref<Task>(),
  events = ref<WarningEvent[]>([]),
  error = ref(''),
  busy = ref(false),
  activeImage = ref(''),
  showOriginal = ref(false),
  reviewing = ref<WarningEvent>(),
  confirmingCancel = ref(false)
const closeButton = ref<HTMLButtonElement>()
let timer: ReturnType<typeof setTimeout>,
  disposed = false,
  fetching = false
const picture = computed(() =>
  task.value?.result ? assetUrl(props.id, activeImage.value || task.value.result.preview) : '',
)
const timingItems = computed(() => {
  const t = task.value?.result?.timings
  if (t && task.value?.result?.workerMode) {
    return [
      { label: '检测引擎启动', value: (t.workerWaitMs ?? 0) + t.startupMs },
      { label: '模型加载', value: t.modelLoadMs + (t.modelHashMs ?? 0) },
      { label: task.value.result.performance?.parallelRegions ? '轨道区域计算' : '轨道危险区定位', value: t.regionMs },
      { label: '目标推理', value: t.inferenceMs },
      ...(t.adviceMs != null ? [{ label: '图像理解与处置建议', value: t.adviceMs }] : []),
      { label: '影像解码与抽帧', value: t.decodeMs },
      ...(t.snapshotMs != null
        ? [
            { label: '危险区判断与目标关联', value: t.associationMs ?? 0 },
            { label: '画面标注', value: t.annotationMs ?? 0 },
            { label: '截图编码与写入', value: t.snapshotMs },
            { label: '进度写入', value: t.progressMs ?? 0 },
          ]
        : [{ label: '标注与截图保存', value: t.postprocessMs }]),
      { label: '结果校验', value: t.otherMs + t.processOverheadMs },
    ].filter((item) => item.value >= 1)
  }
  return t
    ? [
        { label: 'Python 与依赖初始化', value: t.startupMs },
        { label: '模型加载', value: t.modelLoadMs },
        { label: '轨道危险区定位', value: t.regionMs },
        { label: '目标推理', value: t.inferenceMs },
        { label: '影像解码与抽帧', value: t.decodeMs },
        { label: '截图、校验及其他', value: t.postprocessMs + t.otherMs + t.processOverheadMs },
      ].filter((item) => item.value >= 1)
    : []
})
async function load() {
  if (fetching || disposed) return
  fetching = true
  try {
    const previous = task.value?.status
    const [t, e] = await Promise.all([api.task(props.id), api.events(props.id)])
    if (disposed) return
    task.value = t
    events.value = e
    error.value = ''
    if (previous && previous !== t.status) emit('changed')
  } catch (e) {
    if (!disposed) error.value = messageOf(e)
  } finally {
    fetching = false
    if (!disposed && (!task.value || isActive(task.value.status) || error.value))
      timer = setTimeout(load, 1800)
  }
}
async function action(kind: 'retry' | 'cancel') {
  busy.value = true
  confirmingCancel.value = false
  try {
    await api[kind](props.id)
    emit('changed')
    await load()
    emit('notify', kind === 'retry' ? '任务已重新加入检测队列' : '任务已取消')
  } catch (e) {
    emit('notify', messageOf(e))
  } finally {
    busy.value = false
  }
}
async function download(kind: 'report' | 'events.csv') {
  busy.value = true
  try {
    await downloadReport(props.id, kind)
    emit('notify', kind === 'report' ? '报告已导出，打开后可打印或保存为 PDF' : '预警记录已导出')
  } catch (e) {
    emit('notify', messageOf(e))
  } finally {
    busy.value = false
  }
}
function reviewed() {
  void load()
  emit('changed')
}
function selectImage(name: string) {
  activeImage.value = name
  showOriginal.value = false
}
function keyboard(e: KeyboardEvent) {
  if (e.key === 'Escape' && !reviewing.value) emit('close')
}
onMounted(() => {
  void load()
  closeButton.value?.focus()
  window.addEventListener('keydown', keyboard)
})
onBeforeUnmount(() => {
  disposed = true
  clearTimeout(timer)
  window.removeEventListener('keydown', keyboard)
})
</script>
<template>
  <div class="modal-overlay detail-overlay" @click.self="$emit('close')">
    <section class="detail-dialog" role="dialog" aria-modal="true" aria-labelledby="detail-heading">
      <header class="detail-header">
        <span class="detail-header-icon"><FileSearch :size="24" /></span>
        <div>
          <h2 id="detail-heading">{{ task?.title || '加载检测任务…' }}</h2>
          <p>{{ id.slice(0, 8).toUpperCase() }} <span>·</span> {{ dateTime(task?.createdAt) }}</p>
        </div>
        <StatusBadge v-if="task" :status="task.status" /><button
          ref="closeButton"
          class="icon-button"
          aria-label="关闭任务详情"
          @click="$emit('close')"
        >
          <X :size="22" />
        </button>
      </header>
      <div class="detail-body">
        <div v-if="error" class="inline-error" role="alert">
          {{ error }} <button class="text-button" @click="load">重试</button>
        </div>
        <div v-if="!task" class="empty-state">
          <LoaderCircle :size="28" class="spinning" />
          <p>正在读取任务记录</p>
        </div>
        <template v-else>
          <div v-if="isActive(task.status)" class="task-progress-panel">
            <span class="scanning-icon"><LoaderCircle :size="32" class="spinning" /></span>
            <h3>
              {{
                task.status === 'QUEUED'
                  ? '任务已排队，即将开始'
                  : task.progress < 5
                    ? '正在启动检测环境'
                    : task.progress < 10
                      ? '正在加载检测模型'
                      : '正在分析影像与识别目标'
              }}
            </h3>
            <p>关闭此窗口后，任务仍会继续。可在预警与记录中查看进度。</p>
            <div class="progress-track"><div :style="{ width: `${task.progress}%` }"></div></div>
            <div class="progress-caption">
              <span>{{ task.status === 'QUEUED' ? '等待执行' : '检测结果将自动保存' }}</span
              ><b>{{ task.progress }}%</b>
            </div>
            <div v-if="confirmingCancel" class="cancel-confirm">
              <span>确定停止本次检测？已上传的素材会保留。</span
              ><button class="button danger" @click="action('cancel')">确认取消</button
              ><button class="text-button" @click="confirmingCancel = false">继续检测</button>
            </div>
            <button
              v-else
              class="button secondary"
              :disabled="busy"
              @click="confirmingCancel = true"
            >
              <Square :size="13" />取消任务
            </button>
          </div>
          <div v-if="task.status === 'FAILED' || task.status === 'CANCELLED'" class="task-failure">
            <TriangleAlert :size="30" />
            <h3>{{ task.status === 'FAILED' ? '这次检测未能完成' : '任务已取消' }}</h3>
            <p>{{ task.errorMessage || '原始素材和参数已保留，可以重新执行检测。' }}</p>
            <button class="button primary" :disabled="busy" @click="action('retry')">
              <RotateCcw :size="16" />重新执行
            </button>
          </div>
          <template v-if="task.result"
            ><div class="detail-metrics">
              <div>
                <span>发现目标</span><b>{{ task.result.objectCount }}<small>个</small></b>
              </div>
              <div>
                <span>当前预警记录</span
                ><b :class="{ 'text-danger': task.eventCount }"
                  >{{ task.eventCount }}<small>条</small></b
                >
              </div>
              <div>
                <span>区域外目标</span><b>{{ task.result.outsideCount }}<small>个</small></b>
              </div>
              <div>
                <span>总处理耗时</span><b>{{ duration(task.elapsedMs) }}</b>
              </div>
            </div>
            <section
              v-if="task.result.performance && task.mediaType === 'VIDEO'"
              class="performance-panel"
            >
              <div class="performance-heading">
                <b
                  >{{
                    task.result.performance.samplingMode === 'ALL_FRAMES' ? '逐帧检测' : '间隔检测'
                  }}
                  · 实测处理速度</b
                >
                <span
                  >{{ task.result.sampledFrames }} /
                  {{ task.result.performance.sourceFrames }} 帧</span
                >
              </div>
              <div class="performance-metrics">
                <div>
                  <span>视频处理速度</span
                  ><b
                    >{{
                      (
                        task.result.performance.taskFps ?? task.result.performance.pipelineFps
                      ).toFixed(1)
                    }}<small>FPS</small></b
                  >
                </div>
                <div>
                  <span>模型推理吞吐</span
                  ><b>{{ task.result.performance.inferenceFps.toFixed(1) }}<small>FPS</small></b>
                </div>
                <div>
                  <span>原视频帧率</span
                  ><b>{{ task.result.performance.sourceFps.toFixed(1) }}<small>FPS</small></b>
                </div>
              </div>
            </section>
            <details v-if="task.result.timings" class="timing-panel">
              <summary>
                查看耗时明细 · {{ task.result.device === 'CUDA' ? 'GPU 加速' : 'CPU 处理' }} ·
                {{ task.result.sampledFrames }} 帧
              </summary>
              <div class="timing-grid">
                <div v-for="item in timingItems" :key="item.label">
                  <span>{{ item.label }}</span
                  ><b>{{ duration(item.value) }}</b>
                </div>
              </div>
            </details>
            <div v-if="task.result.visionAdvice?.overallAdvice" class="info-note">
              <small>
                {{ task.result.visionAdvice.scope === 'VIDEO' ? '整段视频处置建议' : '图像理解处置建议' }}
                · DeepSeek · 已结合完整检测结果与
                {{ task.result.visionAdvice.generatedSnapshots }} 张关键证据帧
              </small>
              <p>{{ task.result.visionAdvice.overallAdvice }}</p>
            </div>
            <div
              v-else-if="task.result.visionAdvice?.configured && task.result.visionAdvice.errorMessage"
              class="info-note"
            >
              <small>图像理解未生成建议 · 已使用本地规则</small>
              <p>{{ task.result.visionAdvice.errorMessage }}</p>
            </div>
            <div class="detail-result-heading">
              <h3>检测结果</h3>
              <div class="segmented">
                <button :class="{ active: !showOriginal }" @click="showOriginal = false">
                  标注画面</button
                ><button :class="{ active: showOriginal }" @click="showOriginal = true">
                  原始素材
                </button>
              </div>
            </div>
            <VideoFramePreview v-if="showOriginal && task.mediaType === 'VIDEO'" :task-id="id" />
            <div v-else class="result-image-stage">
              <img :src="showOriginal ? sourceUrl(id) : picture"
                :alt="showOriginal ? '原始巡检图片' : '轨道危险区域与目标检测标注图'" />
            </div>
            <div class="result-legend">
              <span><i class="legend-line"></i>轨道危险区</span
              ><span><i class="legend-square red-square"></i>区域内预警</span
              ><span><i class="legend-square green-square"></i>区域外目标</span
              ><b>{{
                task.result.regionSource === 'MANUAL'
                  ? '人工多点标定区域'
                  : task.result.regionSource === 'AUTO_CURVE'
                    ? '自动曲线估计区域 · 请复核'
                    : '自动直线估计区域 · 请复核'
              }}</b>
            </div>
            <div v-if="!events.length" class="result-safe">
              <ShieldCheck :size="20" /><span
                >当前没有预警记录。记录可能已被删除，请结合原始检测画面复核。</span
              >
            </div>
            <div v-if="events.length" class="event-cards">
              <article v-for="event in events" :key="event.id" class="event-card">
                <button class="event-thumbnail" @click="selectImage(event.snapshot)">
                  <img
                    :src="assetUrl(id, event.snapshot)"
                    :alt="`${event.label}预警截图，点击查看`"
                  />
                </button>
                <div>
                  <div class="event-title">
                    <b>{{ event.label }}进入危险区</b
                    ><span class="risk-badge" :class="event.risk.toLowerCase()">{{
                      event.risk === 'HIGH' ? '高风险' : '中风险'
                    }}</span>
                  </div>
                  <p>
                    置信度 {{ (event.confidence * 100).toFixed(1) }}% <span>·</span> 视频位置
                    {{ event.frameTime.toFixed(1) }} s
                  </p>
                  <small>{{ event.adviceSource === 'DEEPSEEK' ? '图像理解建议：' : '本地规则建议：' }}{{ event.advice }}</small>
                  <p v-if="event.reviewNote" class="review-note-text">
                    处理备注：{{ event.reviewNote }}
                  </p>
                </div>
                <div class="event-card-actions">
                  <StatusBadge :status="event.status" /><button
                    class="button small secondary"
                    @click="reviewing = event"
                  >
                    复核 / 处理
                  </button>
                </div>
              </article>
            </div>
            <details v-if="task.result.outsideCount" class="outside-details">
              <summary>查看区域外目标（{{ task.result.outsideCount }} 个，不生成预警）</summary>
              <div v-for="d in task.result.detections.filter((d) => !d.inDanger)" :key="d.trackKey">
                <span
                  >{{ d.label }} · {{ (d.confidence * 100).toFixed(1) }}% ·
                  {{ d.frameTime.toFixed(1) }} s</span
                ><button class="text-button" @click="selectImage(d.snapshot)">查看截图</button>
              </div>
            </details>
            <div class="report-actions">
              <div>
                <Download :size="21" /><span
                  ><b>导出结果</b><small>报告包含检测截图、风险类型与处置建议</small></span
                >
              </div>
              <button class="button secondary" :disabled="busy" @click="download('events.csv')">
                <ArrowDownToLine :size="16" />导出记录</button
              ><button class="button primary" :disabled="busy" @click="download('report')">
                <Download :size="16" />导出巡检报告
              </button>
            </div></template
          >
          <details class="task-log">
            <summary>任务记录</summary>
            <div class="task-parameters">
              <span>置信度：{{ (task.confidence * 100).toFixed(0) }}%</span
              ><span v-if="task.mediaType === 'VIDEO'"
                >视频：{{
                  task.sampleSeconds === 0 ? '逐帧检测' : `每 ${task.sampleSeconds} 秒一帧`
                }}</span
              ><span>执行次数：{{ task.attempt }}</span
              ><span v-if="task.result">处理帧数：{{ task.result.sampledFrames }}</span>
            </div>
            <div v-for="(log, i) in task.logs" :key="i" class="log-line">
              <time>{{ dateTime(log.createdAt) }}</time
              ><span :class="{ 'text-danger': log.level === 'ERROR' }">{{ log.message }}</span>
            </div>
          </details>
        </template>
      </div>
    </section>
  </div>
  <ReviewDialog
    v-if="reviewing"
    :event="reviewing"
    @close="reviewing = undefined"
    @saved="reviewed"
    @notify="$emit('notify', $event)"
  />
</template>
