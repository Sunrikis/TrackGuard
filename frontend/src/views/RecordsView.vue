<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import {
  ArrowUpRight,
  ChevronLeft,
  ChevronRight,
  ClipboardCheck,
  FileSearch,
  FolderOpen,
  RefreshCw,
  Search,
  SlidersHorizontal,
  Trash2,
} from 'lucide-vue-next'
import { api, assetUrl, messageOf } from '../api'
import { categoryLabels, taskLabels, reviewLabels, dateTime, isActive } from '../format'
import type { Task, WarningEvent } from '../types'
import StatusBadge from '../components/StatusBadge.vue'
import ReviewDialog from '../components/ReviewDialog.vue'
import DeleteRecordDialog from '../components/DeleteRecordDialog.vue'
import SortHeading from '../components/SortHeading.vue'
const deleting = ref<{ kind: 'task' | 'event'; id?: string; ids?: string[]; title: string }>()
const props = defineProps<{ tab: 'tasks' | 'events'; refreshKey: number }>()
const emit = defineEmits<{
  'update:tab': [tab: 'tasks' | 'events']
  openTask: [id: string]
  changed: []
  notify: [message: string]
}>()
const tasks = ref<Task[]>([]),
  events = ref<WarningEvent[]>([]),
  query = ref(''),
  status = ref(''),
  category = ref(''),
  eventStatus = ref(''),
  page = ref(1),
  total = ref(0),
  loading = ref(false),
  error = ref(''),
  reviewing = ref<WarningEvent>()
const selected = ref<string[]>([])
const sort = ref('createdAt'), direction = ref<'asc' | 'desc'>('desc'), eventPageSize = ref(20)
const pageSize = computed(() => props.tab === 'tasks' ? 12 : eventPageSize.value)
const headings = computed(() => ({ sort: sort.value, direction: direction.value, disabled: loading.value }))
function sortBy(field: string) {
  direction.value = sort.value === field && direction.value === 'asc' ? 'desc' : 'asc'
  sort.value = field
}
watch(() => props.tab, () => { sort.value = 'createdAt'; direction.value = 'desc' }, { flush: 'sync' })
const selectable = computed(() => props.tab === 'tasks'
  ? tasks.value.filter((task) => !isActive(task.status)).map((task) => task.id)
  : events.value.map((event) => event.id))
const allSelected = computed(() => selectable.value.length > 0 && selected.value.length === selectable.value.length)
function toggleAll() { selected.value = allSelected.value ? [] : [...selectable.value] }
function toggle(id: string) {
  selected.value = selected.value.includes(id) ? selected.value.filter((value) => value !== id) : [...selected.value, id]
}
function deleteSelected() {
  if (!selected.value.length || loading.value) return
  deleting.value = { kind: props.tab === 'tasks' ? 'task' : 'event', ids: [...selected.value],
    title: `已选择 ${selected.value.length} ${props.tab === 'tasks' ? '项检测任务（含关联预警和日志）' : '条预警记录'}` }
}
watch(selectable, (ids) => { selected.value = selected.value.filter((id) => ids.includes(id)) })
watch([query, status, category, eventStatus, page, sort, direction, eventPageSize, () => props.tab], () => { selected.value = [] }, { flush: 'sync' })
function deleted() { selected.value = []; reviewed() }
let sequence = 0,
  timer: ReturnType<typeof setTimeout>,
  interval: ReturnType<typeof setInterval>
async function load() {
  const seq = ++sequence
  loading.value = true
  try {
    if (props.tab === 'tasks') {
      const data = await api.tasks(query.value, status.value, page.value, pageSize.value, sort.value, direction.value)
      if (seq === sequence) {
        tasks.value = data.items
        total.value = data.total
        if (page.value > Math.max(1, Math.ceil(data.total / pageSize.value))) page.value = Math.max(1, Math.ceil(data.total / pageSize.value))
      }
    } else {
      const data = await api.eventPage(category.value, eventStatus.value, page.value, pageSize.value, sort.value, direction.value)
      if (seq === sequence) {
        events.value = data.items
        total.value = data.total
        if (page.value > Math.max(1, Math.ceil(data.total / pageSize.value))) page.value = Math.max(1, Math.ceil(data.total / pageSize.value))
      }
    }
    if (seq === sequence) error.value = ''
  } catch (e) {
    if (seq === sequence) error.value = messageOf(e)
  } finally {
    if (seq === sequence) loading.value = false
  }
}
watch(query, () => {
  clearTimeout(timer)
  page.value = 1
  timer = setTimeout(load, 300)
})
watch([status, category, eventStatus, sort, direction, eventPageSize, () => props.tab], () => {
  page.value = 1
  void load()
})
watch([page, () => props.refreshKey], () => void load())
function reviewed() {
  void load()
  emit('changed')
}
onMounted(() => {
  void load()
  interval = setInterval(() => {
    if (!loading.value) void load()
  }, 10000)
})
onBeforeUnmount(() => {
  sequence++
  clearTimeout(timer)
  clearInterval(interval)
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>预警与记录<span class="heading-dot">.</span></h1>
      <p>查询、复核或删除检测记录。</p>
    </div>
    <button class="button secondary" :disabled="loading" @click="load">
      <RefreshCw :size="16" :class="{ spinning: loading }" />刷新记录
    </button>
  </div>
  <section class="panel records-panel">
    <div class="record-tabs">
      <button :class="{ active: tab === 'tasks' }" @click="$emit('update:tab', 'tasks')">
        <FileSearch :size="17" />检测任务</button
      ><button :class="{ active: tab === 'events' }" @click="$emit('update:tab', 'events')">
        <ClipboardCheck :size="17" />预警处理
      </button>
    </div>
    <div class="record-filters">
      <template v-if="tab === 'tasks'"
        ><label class="search-field"
          ><Search :size="18" /><input
            v-model="query"
            placeholder="搜索任务名称或文件名"
            aria-label="搜索任务"
            maxlength="100" /></label
        ><select v-model="status" class="input filter-select" aria-label="任务状态筛选">
          <option value="">全部状态</option>
          <option v-for="(label, key) in taskLabels" :key="key" :value="key">
            {{ label }}
          </option></select
        ><span class="record-count">共 {{ total }} 项任务</span></template
      ><template v-else
        ><span class="filter-label"><SlidersHorizontal :size="17" />筛选预警</span
        ><select v-model="category" class="input filter-select" aria-label="异物类型筛选">
          <option value="">全部异物类型</option>
          <option v-for="(label, key) in categoryLabels" :key="key" :value="key">
            {{ label }}
          </option></select
        ><select v-model="eventStatus" class="input filter-select" aria-label="处理状态筛选">
          <option value="">全部处理状态</option>
          <option v-for="(label, key) in reviewLabels" :key="key" :value="key">
            {{ label }}
          </option></select
        ><span class="record-count"
          >共 {{ total }} 条预警</span
        ></template
      >
    </div>
    <div v-if="error" class="inline-error records-error" role="alert">{{ error }}</div>
    <div class="batch-toolbar">
      <span aria-live="polite">{{ selected.length ? `已选择 ${selected.length} 条` : '在第一列选择要删除的记录' }}</span>
      <button class="text-button" :disabled="!selected.length || loading" @click="selected = []">取消选择</button>
      <button class="button small danger" :disabled="!selected.length || loading" @click="deleteSelected"><Trash2 :size="15" />批量删除</button>
      <small>表头复选框选择当前页；切换分页、排序或筛选后会清空选择。{{ tab === 'tasks' ? '进行中的任务不可删除。' : '' }}</small>
    </div>
    <div v-if="tab === 'tasks' && tasks.length" class="table-scroll">
      <table class="data-table">
        <thead>
          <tr>
            <th class="selection-cell"><input type="checkbox" :checked="allSelected" :indeterminate="selected.length > 0 && !allSelected" :disabled="loading || !selectable.length" aria-label="全选当前页可删除任务" @change="toggleAll" /></th>
            <SortHeading field="title" label="任务名称 / 编号" v-bind="headings" @sort-by="sortBy" />
            <SortHeading field="mediaType" label="素材类型" v-bind="headings" @sort-by="sortBy" />
            <SortHeading field="modelName" label="检测模型" v-bind="headings" @sort-by="sortBy" />
            <SortHeading field="createdAt" label="检测时间" v-bind="headings" @sort-by="sortBy" />
            <SortHeading field="status" label="检测状态" v-bind="headings" @sort-by="sortBy" />
            <SortHeading field="eventCount" label="预警" v-bind="headings" @sort-by="sortBy" />
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="task in tasks" :key="task.id">
            <td class="selection-cell"><input type="checkbox" :checked="selected.includes(task.id)" :disabled="loading || isActive(task.status)" :aria-label="`选择任务：${task.title}`" @change="toggle(task.id)" /></td>
            <td>
              <button class="task-link" @click="$emit('openTask', task.id)">
                <span class="file-icon"><FileSearch :size="19" /></span
                ><span
                  ><b>{{ task.title }}</b
                  ><small>{{ task.id.slice(0, 8).toUpperCase() }}</small></span
                >
              </button>
            </td>
            <td>{{ task.mediaType === 'IMAGE' ? '图片' : '短视频' }}</td>
            <td class="model-cell">{{ task.modelName }}</td>
            <td>{{ dateTime(task.createdAt) }}</td>
            <td>
              <StatusBadge :status="task.status" /><span
                v-if="task.status === 'RUNNING'"
                class="table-progress"
                >{{ task.progress }}%</span
              >
            </td>
            <td>
              <b :class="{ 'text-danger': task.eventCount }">{{ task.eventCount }}</b> 条
            </td>
            <td>
              <button class="table-action" @click="$emit('openTask', task.id)">
                查看详情 <ArrowUpRight :size="14" />
              </button>
              <button class="table-action delete-action" :disabled="isActive(task.status)" :title="isActive(task.status) ? '请先取消任务，等待检测停止后再删除' : '删除任务及关联预警'" :aria-label="`删除任务：${task.title}`" @click="deleting = { kind: 'task', id: task.id, title: task.title }"><Trash2 :size="14" />删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else-if="tab === 'events' && events.length" class="table-scroll">
      <table class="data-table event-table">
        <thead>
          <tr>
            <th class="selection-cell"><input type="checkbox" :checked="allSelected" :indeterminate="selected.length > 0 && !allSelected" :disabled="loading || !selectable.length" aria-label="全选当前页预警" @change="toggleAll" /></th>
            <SortHeading field="label" label="异物 / 检测截图" v-bind="headings" @sort-by="sortBy" />
            <SortHeading field="taskTitle" label="来源任务" v-bind="headings" @sort-by="sortBy" />
            <SortHeading field="risk" label="风险等级" v-bind="headings" @sort-by="sortBy" />
            <SortHeading field="createdAt" label="发现时间" v-bind="headings" @sort-by="sortBy" />
            <SortHeading field="status" label="处理状态" v-bind="headings" @sort-by="sortBy" />
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="event in events" :key="event.id">
            <td class="selection-cell"><input type="checkbox" :checked="selected.includes(event.id)" :disabled="loading" :aria-label="`选择预警：${event.label}，${event.taskTitle}`" @change="toggle(event.id)" /></td>
            <td>
              <div class="event-cell">
                <img
                  :src="assetUrl(event.taskId, event.snapshot)"
                  :alt="event.label + '检测截图'"
                  loading="lazy"
                /><span
                  ><b>{{ event.label }}</b
                  ><small>{{ (event.confidence * 100).toFixed(1) }}% 置信度</small></span
                >
              </div>
            </td>
            <td>
              <button class="plain-link ellipsis" @click="$emit('openTask', event.taskId)">
                {{ event.taskTitle }}
              </button>
              <small class="event-model">{{ event.modelName }}</small>
            </td>
            <td>
              <span class="risk-badge" :class="event.risk.toLowerCase()">{{
                event.risk === 'HIGH' ? '高风险' : '中风险'
              }}</span>
            </td>
            <td>{{ dateTime(event.createdAt) }}</td>
            <td><StatusBadge :status="event.status" /></td>
            <td>
              <button class="table-action" @click="reviewing = event">
                复核处理 <ArrowUpRight :size="14" />
              </button>
              <button class="table-action delete-action" :aria-label="`删除预警：${event.label}，${event.taskTitle}`" @click="deleting = { kind: 'event', id: event.id, title: `${event.label} · ${event.taskTitle}` }"><Trash2 :size="14" />删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="empty-state">
      <span class="empty-icon"><FolderOpen :size="30" /></span>
      <h4>{{ loading ? '正在读取记录…' : '暂无符合条件的记录' }}</h4>
      <p>
        {{
          tab === 'tasks'
            ? '上传素材完成检测后，任务会自动保存在这里。'
            : '检测发现危险区内的目标后，将自动生成可复核的预警。'
        }}
      </p>
    </div>
    <div class="pagination">
      <label v-if="tab === 'events'">每页 <select v-model.number="eventPageSize" :disabled="loading" aria-label="每页预警条数"><option :value="20">20 条</option><option :value="50">50 条</option><option :value="100">100 条</option></select></label>
      <span>共 {{ total }} 条 · 第 {{ page }} 页 / 共 {{ Math.max(1, Math.ceil(total / pageSize)) }} 页</span
      ><button
        class="button small secondary"
        :disabled="page === 1 || loading"
        aria-label="上一页"
        @click="page--"
      >
        <ChevronLeft :size="16" /></button
      ><button
        class="button small secondary"
        :disabled="page * pageSize >= total || loading"
        aria-label="下一页"
        @click="page++"
      >
        <ChevronRight :size="16" />
      </button>
    </div>
  </section>
  <div class="info-note record-note">
    <ClipboardCheck :size="18" />
    <p>
      将预警标记为“误报”后，该记录仍然保留用于追溯，并从有效预警统计中排除。请填写具体复核原因。
    </p>
  </div>
  <ReviewDialog
    v-if="reviewing"
    :event="reviewing"
    @close="reviewing = undefined"
    @saved="reviewed"
    @notify="$emit('notify', $event)"
  />
  <DeleteRecordDialog v-if="deleting" v-bind="deleting" @close="deleting = undefined" @deleted="deleted" @notify="$emit('notify', $event)" />
</template>
<style scoped>
.selection-cell { width: 56px; }
.delete-action { color: #c2414b; margin-left: 12px; }
.delete-action:disabled { opacity: .4; cursor: not-allowed; }
.batch-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 14px; padding: 14px 24px; background: #f7f9fc; border-bottom: 1px solid #e8edf5; font-size: 13px; }
.batch-toolbar label { display: flex; align-items: center; gap: 8px; cursor: pointer; }
.batch-toolbar small { flex-basis: 100%; color: #667085; }
input[type='checkbox'] { width: 16px; height: 16px; accent-color: #5579ff; cursor: pointer; }
input:disabled { cursor: not-allowed; }
</style>
