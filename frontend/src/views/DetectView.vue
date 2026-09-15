<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ArrowRight,
  Crosshair,
  FileImage,
  Film,
  Info,
  LoaderCircle,
  MousePointer2,
  RotateCcw,
  ScanLine,
  ShieldCheck,
  SlidersHorizontal,
  UploadCloud,
  X,
} from 'lucide-vue-next'
import { api, messageOf } from '../api'
import { useVideoFrames } from '../useVideoFrames'
import { validRegion, categoryLabels } from '../format'
import type { Category, DetectionModel, Point, Task } from '../types'
const props = defineProps<{ connected: boolean }>()
const emit = defineEmits<{ created: [task: Task]; notify: [message: string] }>()
const file = ref<File>(),
  title = ref(''),
  confidence = ref(50),
  sample = ref(1),
  samplingMode = ref('sampled'),
  mode = ref('auto'),
  points = ref<Point[]>([])
const fileInput = ref<HTMLInputElement>(),
  preview = ref(''),
  dragging = ref(false),
  submitting = ref(false),
  progress = ref(0),
  error = ref('')
const isVideo = computed(() => !!file.value && /\.(mp4|mov|m4v)$/i.test(file.value.name))
const models = ref<DetectionModel[]>([]),
  modelId = ref(''),
  modelError = ref(''),
  loadingModels = ref(false)
const targets = ref<Category[]>(['person', 'vehicle', 'motorcycle', 'animal', 'obstacle'])
const { image: poster, duration: videoDuration, time: frameTime, loading: frameLoading,
  error: previewError, open: openVideo, seek: seekFrame, reset: resetVideo } = useVideoFrames()
const selectedModel = computed(() => models.value.find((m) => m.id === modelId.value))
const estimatedFrames = computed(() => Math.ceil(videoDuration.value / sample.value))
async function loadModels() {
  loadingModels.value = true
  modelError.value = ''
  try {
    models.value = (await api.models()).filter((model) => model.available)
    if (!selectedModel.value)
      modelId.value =
        (models.value.find((model) => model.isDefault) || models.value[0])?.id || ''
  } catch (e) {
    modelError.value = messageOf(e)
  } finally {
    loadingModels.value = false
  }
}
watch(selectedModel, (model) => {
  if (!model) return
  targets.value = targets.value.filter((c) => model.categories.includes(c))
  if (!targets.value.length) targets.value = [...model.categories]
})
watch(
  () => props.connected,
  (connected) => {
    if (connected && !models.value.length) void loadModels()
  },
)
onMounted(() => {
  void loadModels()
})
const imageSource = computed(() => (isVideo.value ? poster.value : preview.value))
const ready = computed(
  () =>
    file.value &&
    props.connected &&
    selectedModel.value?.available &&
    targets.value.length > 0 &&
    !submitting.value &&
    (mode.value === 'auto' ||
      (!!imageSource.value &&
        !frameLoading.value &&
        !previewError.value &&
        validRegion(points.value))),
)
function release() {
  if (preview.value) URL.revokeObjectURL(preview.value)
  resetVideo()
}
function choose(selected?: File) {
  if (!selected) return
  error.value = ''
  previewError.value = ''
  if (!/\.(jpe?g|png|mp4|mov|m4v)$/i.test(selected.name)) {
    error.value = '请选择 JPG、PNG 图片或 MP4、MOV、M4V 短视频'
    return
  }
  if (!selected.size || selected.size > 100 * 1024 * 1024) {
    error.value = '文件不能为空，且大小不能超过 100 MB'
    return
  }
  release()
  file.value = selected
  title.value = selected.name.replace(/\.[^.]+$/, '').slice(0, 100)
  points.value = []
  poster.value = ''
  videoDuration.value = 0
  frameTime.value = 0
  preview.value = URL.createObjectURL(selected)
  if (isVideo.value) void openVideo(selected)
}

function inputChanged(event: Event) {
  choose((event.target as HTMLInputElement).files?.[0])
  if (fileInput.value) fileInput.value.value = ''
}
function drop(event: DragEvent) {
  dragging.value = false
  if (!submitting.value) choose(event.dataTransfer?.files[0])
}
function clearFile() {
  release()
  file.value = undefined
  preview.value = ''
  poster.value = ''
  points.value = []
  error.value = ''
  previewError.value = ''
  videoDuration.value = 0
  frameTime.value = 0
}
function mark(event: MouseEvent) {
  if (
    mode.value !== 'manual' ||
    points.value.length >= 12 ||
    submitting.value ||
    frameLoading.value ||
    previewError.value
  )
    return
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  points.value.push([
    (event.clientX - rect.left) / rect.width,
    (event.clientY - rect.top) / rect.height,
  ])
}
async function submit() {
  if (!ready.value || !file.value) return
  submitting.value = true
  error.value = ''
  progress.value = 0
  try {
    emit(
      'created',
      await api.upload(
        file.value,
        title.value,
        confidence.value / 100,
        samplingMode.value === 'all' ? 0 : sample.value,
        mode.value === 'manual' ? points.value : [],
        modelId.value,
        targets.value,
        (n) => (progress.value = n),
      ),
    )
  } catch (e) {
    error.value = messageOf(e)
  } finally {
    submitting.value = false
  }
}
onBeforeUnmount(release)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>检测工作台<span class="heading-dot">.</span></h1>
      <p>上传图片或短视频，设置检测区域和目标类别。</p>
    </div>
    <span class="soft-badge"><ShieldCheck :size="15" />本地检测 · 自动留存</span>
  </div>
  <div class="workflow-steps">
    <span class="current"><b>01</b>上传素材</span>
    <div></div>
    <span :class="{ current: !!file }"><b>02</b>确认检测区域</span>
    <div></div>
    <span><b>03</b>检测与复核</span>
  </div>
  <div class="detection-layout">
    <div class="detection-main">
      <section class="panel upload-panel">
        <div class="panel-heading">
          <div>
            <h3><span class="section-number">01</span> 巡检素材</h3>
            <p>支持轨道图片与固定机位短视频</p>
          </div>
          <span class="tiny-label">最大 100 MB</span>
        </div>
        <input
          ref="fileInput"
          type="file"
          accept=".jpg,.jpeg,.png,.mp4,.mov,.m4v"
          hidden
          @change="inputChanged"
        />
        <button
          v-if="!file"
          class="upload-zone"
          :class="{ dragging }"
          @click="fileInput?.click()"
          @dragover.prevent="dragging = true"
          @dragleave.prevent="dragging = false"
          @drop.prevent="drop"
        >
          <span class="upload-icon"><UploadCloud :size="34" :stroke-width="1.5" /></span
          ><strong>拖拽文件至此，或<span>点击上传</span></strong>
          <p>JPG、PNG 图片 / MP4、MOV、M4V 短视频</p>
          <div class="upload-specs">
            <span><FileImage :size="15" />清晰的轨道画面</span
            ><span><Film :size="15" />视频不超过 120 秒</span>
          </div>
        </button>
        <div v-else class="selected-media">
          <div class="selected-file">
            <span class="file-icon"><component :is="isVideo ? Film : FileImage" :size="20" /></span>
            <div>
              <b>{{ file.name }}</b
              ><small
                >{{ (file.size / 1024 / 1024).toFixed(2) }} MB ·
                {{ isVideo ? '短视频' : '图片' }}</small
              >
            </div>
            <button
              class="icon-button"
              aria-label="移除文件"
              :disabled="submitting"
              @click="clearFile"
            >
              <X :size="18" />
            </button>
          </div>
          <div v-if="isVideo" class="video-calibration-controls">
            <div class="range-heading">
              <label for="calibration-time">拖动进度条选择视频画面</label>
              <b
                >{{ frameTime.toFixed(1) }}<small> / {{ videoDuration.toFixed(1) }} 秒</small></b
              >
            </div>
            <input
              id="calibration-time"
              type="range"
              min="0"
              :max="Math.max(0, videoDuration - 0.05)"
              step="0.05"
              :value="frameTime"
              :disabled="!videoDuration || submitting"
              @input="seekFrame"
              :aria-valuetext="`视频第 ${frameTime.toFixed(1)} 秒`"
            />
            <p class="field-hint">
              拖动进度条查看静态画面。手动标定时，沿危险区边界依次点击 4–12 个点；切换画面会保留标定。
            </p>
          </div>
          <p v-if="isVideo && frameLoading" class="field-hint" role="status">正在读取视频画面…</p>
          <div
            v-if="imageSource"
            class="calibration-stage"
            :class="{ marking: mode === 'manual' && !frameLoading && !submitting }"
            :aria-busy="frameLoading"
          >
            <div class="calibration-image" @click="mark">
              <img
                :src="imageSource"
                alt="待检测轨道画面"
                @error="previewError = '图片预览失败，请检查文件是否完整'"
              /><svg v-if="mode === 'manual'" viewBox="0 0 1000 1000" preserveAspectRatio="none">
                <polygon
                  v-if="points.length > 2"
                  :points="points.map((p) => `${p[0] * 1000},${p[1] * 1000}`).join(' ')"
                  fill="#5579ff38"
                  stroke="#6383ff"
                  stroke-width="3"
                />
                <polyline
                  v-else
                  :points="points.map((p) => `${p[0] * 1000},${p[1] * 1000}`).join(' ')"
                  fill="none"
                  stroke="#6383ff"
                  stroke-width="3"
                />
                <g v-for="(point, i) in points" :key="i">
                  <circle
                    :cx="point[0] * 1000"
                    :cy="point[1] * 1000"
                    r="11"
                    fill="white"
                    stroke="#5878ed"
                    stroke-width="5"
                  />
                </g></svg
              ><span
                v-for="(point, i) in mode === 'manual' ? points : []"
                :key="i"
                class="point-label"
                :style="{ left: `${point[0] * 100}%`, top: `${point[1] * 100}%` }"
                >{{ i + 1 }}</span
              >
            </div>
            <div class="preview-footer">
              <span
                ><component :is="mode === 'manual' ? MousePointer2 : ScanLine" :size="15" />{{
                  mode === 'manual'
                    ? `沿边界依次标点 ${points.length}/4–12`
                    : '检测时自动估计直线或平滑弯道区域'
                }}</span
              ><button
                v-if="mode === 'manual'"
                class="text-button"
                :disabled="submitting || !points.length"
                @click="points.pop()"
              >
                撤销上一点
              </button>
              <button
                v-if="mode === 'manual'"
                class="text-button"
                :disabled="submitting || !points.length"
                @click="points = []"
              >
                <RotateCcw :size="14" />重置标定
              </button>
            </div>
          </div>
          <p v-if="previewError" class="inline-warning"><Info :size="16" />{{ previewError }}<button v-if="isVideo && file" class="text-button" @click="openVideo(file)">重新读取</button></p>
          <button
            class="text-button replace-file"
            :disabled="submitting"
            @click="fileInput?.click()"
          >
            <UploadCloud :size="15" />更换文件
          </button>
        </div>
      </section>
      <section class="panel region-panel">
        <div class="panel-heading">
          <div>
            <h3><span class="section-number">02</span> 轨道危险区</h3>
            <p>只对进入危险区的目标生成预警，减少轨道外误报</p>
          </div>
          <Crosshair :size="21" class="muted" />
        </div>
        <div class="region-options">
          <label :class="{ selected: mode === 'auto' }"
            ><input v-model="mode" type="radio" value="auto" :disabled="submitting" /><span
              ><b>自动识别</b><small>支持清晰直线与平滑弯道</small></span
            ><ScanLine :size="22" /></label
          ><label :class="{ selected: mode === 'manual' }"
            ><input v-model="mode" type="radio" value="manual" :disabled="submitting" /><span
              ><b>手动标定钢轨范围</b
              ><small>{{
                 isVideo ? '选择视频画面，沿边界标记 4–12 个点' : '在预览图上沿边界标记 4–12 个点'
              }}</small></span
            ><MousePointer2 :size="22"
          /></label>
        </div>
        <p
          v-if="mode === 'manual' && points.length >= 4 && !validRegion(points)"
          class="inline-error"
        >
          区域交叉、过小或形状无效，请重置后重新标定。
        </p>
      </section>
    </div>
    <aside class="detection-side">
      <section class="panel parameter-panel">
        <div class="panel-heading">
          <h3>检测设置</h3>
          <SlidersHorizontal :size="18" class="muted" />
        </div>
        <label class="field-label" for="task-title">任务名称</label
        ><input
          id="task-title"
          v-model="title"
          class="input"
          maxlength="100"
          placeholder="例如：东侧线路日常巡检"
          :disabled="submitting"
        />
        <label class="field-label model-label" for="detection-model">检测模型</label>
        <select
          id="detection-model"
          v-model="modelId"
          class="input"
          :disabled="submitting || loadingModels"
        >
          <option v-if="!models.length" value="">
            {{ loadingModels ? '正在读取模型…' : '暂无可用模型' }}
          </option>
          <option
            v-for="model in models"
            :key="model.id"
            :value="model.id"
          >
            {{ model.name }}
          </option>
        </select>
        <p v-if="selectedModel" class="field-hint model-description">
          {{ selectedModel.description }}
        </p>
        <div v-if="modelError" class="inline-error">
          {{ modelError }} <button class="text-button" @click="loadModels">重新读取</button>
        </div>
        <div class="range-heading">
          <label for="confidence">置信度阈值</label><b>{{ confidence }}<small>%</small></b>
        </div>
        <input
          id="confidence"
          v-model.number="confidence"
          type="range"
          min="10"
          max="95"
          step="5"
          :disabled="submitting"
        />
        <div class="range-labels"><span>更高召回</span><span>更高置信度</span></div>
        <p class="field-hint">阈值越高，保留的目标越可信，但可能遗漏小目标。</p>
        <template v-if="isVideo">
          <label class="field-label model-label" for="sampling-mode">视频检测方式</label>
          <select id="sampling-mode" v-model="samplingMode" class="input" :disabled="submitting">
            <option value="sampled">间隔检测（推荐）</option>
            <option value="all">逐帧检测</option>
          </select>
        </template>
        <template v-if="isVideo && samplingMode === 'sampled'">
          <div class="range-heading">
            <label for="sample-seconds">视频抽帧间隔</label
            ><b>{{ sample }}<small>秒 / 帧</small></b>
          </div>
          <input
            id="sample-seconds"
            v-model.number="sample"
            type="range"
            min="0.5"
            max="5"
            step="0.5"
            :aria-valuetext="`每 ${sample} 秒检测一帧`"
            :disabled="submitting"
          />
          <div class="range-labels"><span>0.5 秒 · 更细致</span><span>5 秒 · 更快速</span></div>
          <p v-if="videoDuration" class="field-hint">预计检测 {{ estimatedFrames }} 帧</p>
        </template>
        <div class="target-types">
          <div class="target-heading">
            <span id="target-label" class="field-label">识别目标 <small>可多选</small></span
            ><button
              class="text-button"
              :disabled="submitting || !selectedModel"
              @click="targets = [...(selectedModel?.categories || [])]"
            >
              全选
            </button>
          </div>
          <div class="target-checkboxes" role="group" aria-labelledby="target-label">
            <label
              v-for="(label, key) in categoryLabels"
              :key="key"
              :class="{ selected: targets.includes(key) }"
              ><input
                v-model="targets"
                type="checkbox"
                :value="key"
                :disabled="submitting || !selectedModel?.categories.includes(key)"
              /><span>{{ label }}</span></label
            >
          </div>
          <p v-if="!targets.length" class="inline-error">请至少选择一种识别目标。</p>
        </div>
        <div v-if="error" class="inline-error" role="alert">{{ error }}</div>
        <div v-if="!connected" class="inline-warning">服务未连接，请先启动后端。</div>
        <div v-else-if="!selectedModel?.available && !loadingModels" class="inline-warning">
          请选择已就绪的检测模型。
        </div>
        <button class="button primary full detect-submit" :disabled="!ready" @click="submit">
          <LoaderCircle v-if="submitting" class="spinning" :size="18" /><ScanLine
            v-else
            :size="18"
          />{{ submitting ? `上传中 ${progress}%` : '开始检测'
          }}<ArrowRight v-if="!submitting" :size="17" />
        </button>
      </section>
    </aside>
  </div>
</template>
