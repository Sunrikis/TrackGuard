<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  Activity,
  ArrowUpRight,
  Bell,
  ChartNoAxesCombined,
  ChevronRight,
  CircleHelp,
  LayoutDashboard,
  Menu,
  RefreshCw,
  ScanLine,
  ShieldCheck,
  TrainFront,
  X,
  ClipboardList,
} from 'lucide-vue-next'
import { api, messageOf } from './api'
import type { Page, Statistics, Task, WarningEvent, UserProfile } from './types'
import Dashboard from './views/Dashboard.vue'
import DetectView from './views/DetectView.vue'
import RecordsView from './views/RecordsView.vue'
import StatisticsView from './views/StatisticsView.vue'
import TaskDetail from './components/TaskDetail.vue'
import AuthView from './views/AuthView.vue'
import ProfileDialog from './components/ProfileDialog.vue'
const navigation = [
  { id: 'overview', label: '巡检总览', en: 'Overview', icon: LayoutDashboard },
  { id: 'detect', label: '检测工作台', en: 'Detection', icon: ScanLine },
  { id: 'records', label: '预警与记录', en: 'Records', icon: ClipboardList },
  { id: 'statistics', label: '统计分析', en: 'Analytics', icon: ChartNoAxesCombined },
] as const
const page = ref<Page>('overview'),
  mobileMenu = ref(false),
  help = ref(false)
const stats = ref<Statistics>(),
  tasks = ref<Task[]>([]),
  events = ref<WarningEvent[]>([])
const loading = ref(false),
  connected = ref(false),
  error = ref(''),
  selectedTask = ref(''),
  toast = ref('')
const recordTab = ref<'tasks' | 'events'>('tasks')
const refreshKey = ref(0)
const user = ref<UserProfile>(),
  sessionReady = ref(false),
  sessionError = ref(''),
  showProfile = ref(false)
let sessionGeneration = 0,
  bootstrapping = false
let timer: ReturnType<typeof setInterval>,
  toastTimer: ReturnType<typeof setTimeout>,
  refreshing = false
const currentNav = computed(() => navigation.find((n) => n.id === page.value)!)
function notify(message: string) {
  toast.value = message
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toast.value = ''), 4200)
}
function navigate(id: Page) {
  page.value = id
  mobileMenu.value = false
}
async function refresh(manual = false) {
  if (refreshing || !user.value) return
  const generation = sessionGeneration
  refreshing = true
  if (manual) loading.value = true
  try {
    const [, s, t, e] = await Promise.all([
      api.health(),
      api.stats(),
      api.tasks('', '', 1, 6),
      api.events(),
    ])
    if (generation !== sessionGeneration) return
    stats.value = s
    tasks.value = t.items
    events.value = e
    connected.value = true
    error.value = ''
  } catch (e) {
    if (generation !== sessionGeneration) return
    connected.value = false
    error.value = messageOf(e)
    if (manual) notify(error.value)
  } finally {
    refreshing = false
    loading.value = false
  }
}
function changed() {
  refreshKey.value++
  void refresh()
}
function created(task: Task) {
  selectedTask.value = task.id
  changed()
  notify('检测任务已创建，正在处理')
}
function showWarnings() {
  recordTab.value = 'events'
  navigate('records')
}
function startFromHelp() {
  help.value = false
  navigate('detect')
}
async function bootstrap() {
  if (bootstrapping) return
  bootstrapping = true
  sessionReady.value = false
  sessionError.value = ''
  try {
    const session = await api.session()
    user.value = session.user
    sessionReady.value = true
    if (user.value) void refresh(true)
  } catch (e) {
    sessionError.value = messageOf(e)
  } finally {
    bootstrapping = false
  }
}
function authenticated(profile: UserProfile) {
  sessionGeneration++
  user.value = profile
  page.value = 'overview'
  void refresh(true)
}
function clearWorkspace() {
  sessionGeneration++
  user.value = undefined
  tasks.value = []
  events.value = []
  stats.value = undefined
  selectedTask.value = ''
  showProfile.value = false
  help.value = false
  mobileMenu.value = false
  connected.value = false
  error.value = ''
  page.value = 'overview'
}
async function logout() {
  try {
    await api.logout()
    clearWorkspace()
    await bootstrap()
    notify('已退出登录')
  } catch (e) {
    notify(messageOf(e))
  }
}
function expired() {
  if (!user.value) return
  clearWorkspace()
  notify('登录已过期，请重新登录')
  void bootstrap()
}
onMounted(() => {
  void bootstrap()
  window.addEventListener('session-expired', expired)
  timer = setInterval(() => {
    void refresh()
  }, 10000)
})
onBeforeUnmount(() => {
  window.removeEventListener('session-expired', expired)
  clearInterval(timer)
  clearTimeout(toastTimer)
})
</script>
<template>
  <AuthView
    v-if="!user"
    :ready="sessionReady"
    :connection-error="sessionError"
    @authenticated="authenticated"
    @reconnect="bootstrap"
    @notify="notify"
  />
  <div v-else class="app-shell">
    <div v-if="mobileMenu" class="sidebar-scrim" @click="mobileMenu = false"></div>
    <aside class="sidebar" :class="{ 'is-open': mobileMenu }">
      <a href="#" class="brand" @click.prevent="navigate('overview')"
        ><span class="brand-mark"><TrainFront :size="24" /></span
        ><span
          ><strong>轨安 <i>TrackGuard</i></strong
          ><small>铁路轨道安全巡检平台</small></span
        ></a
      >
      <div class="workspace-label">功能导航</div>
      <nav aria-label="主导航">
        <button
          v-for="item in navigation"
          :key="item.id"
          :class="{ active: page === item.id }"
          @click="navigate(item.id)"
        >
          <component :is="item.icon" :size="19" /><span>{{ item.label }}</span
          ><span v-if="item.id === 'records' && stats?.pendingCount" class="nav-count">{{
            stats.pendingCount
          }}</span
          ><ChevronRight v-else-if="page === item.id" :size="15" class="nav-arrow" />
        </button>
      </nav>
      <div class="sidebar-bottom">
        <button class="help-link" @click="help = true">
          <CircleHelp :size="17" />使用指南<ArrowUpRight :size="15" />
        </button>
        <button
          class="sidebar-footer account-button"
          aria-label="打开个人资料"
          @click="showProfile = true"
        >
          <span class="avatar"
            ><img v-if="user.avatarUrl" :src="user.avatarUrl" alt="个人头像" /><template v-else>{{
              user.displayName.slice(0, 1)
            }}</template></span
          ><span
            ><b>{{ user.displayName }}</b
            ><small>@{{ user.username }}</small></span
          ><span class="online-dot" :class="{ offline: !connected }"></span>
        </button>
      </div>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <div class="breadcrumb">
          <button
            class="icon-button mobile-menu"
            aria-label="展开导航"
            @click="mobileMenu = !mobileMenu"
          >
            <Menu :size="21" /></button
          ><span class="breadcrumb-home">工作空间</span><ChevronRight :size="14" /><b>{{
            currentNav.label
          }}</b>
        </div>
        <div class="topbar-actions">
          <span class="connection" :class="{ offline: !connected }"
            ><span class="online-dot" :class="{ offline: !connected }"></span
            >{{ connected ? '服务已连接' : '服务未连接' }}</span
          >
          <div class="topbar-divider"></div>
          <button class="icon-button" title="刷新数据" aria-label="刷新数据" @click="refresh(true)">
            <RefreshCw :size="17" :class="{ spinning: loading }" /></button
          ><button
            class="icon-button notification-button"
            title="待处理预警"
            aria-label="查看待处理预警"
            @click="showWarnings"
          >
            <Bell :size="19" /><span v-if="stats?.pendingCount"></span></button
          ><button class="top-avatar" aria-label="个人资料与头像" @click="showProfile = true">
            <img v-if="user.avatarUrl" :src="user.avatarUrl" alt="个人头像" /><span v-else>{{
              user.displayName.slice(0, 1)
            }}</span>
          </button>
        </div>
      </header>
      <main class="main-content">
        <div v-if="error" class="service-banner" role="alert">
          <Activity :size="18" /><span>{{ error }}。恢复连接后可继续检测，当前数据可能未更新。</span
          ><button @click="refresh(true)">重新连接</button>
        </div>
        <Dashboard
          v-if="page === 'overview'"
          :stats="stats"
          :tasks="tasks"
          :events="events"
          :loading="loading"
          @navigate="navigate"
          @open-task="selectedTask = $event"
          @warnings="showWarnings"
        />
        <DetectView
          v-else-if="page === 'detect'"
          :connected="connected"
          @created="created"
          @notify="notify"
        />
        <RecordsView
          v-else-if="page === 'records'"
          v-model:tab="recordTab"
          :refresh-key="refreshKey"
          @open-task="selectedTask = $event"
          @notify="notify"
          @changed="changed"
        />
        <StatisticsView v-else :refresh-key="refreshKey" @notify="notify" />
        <footer class="page-footer"><span>TrackGuard · 本地部署</span></footer>
      </main>
    </div>
    <TaskDetail
      v-if="selectedTask"
      :id="selectedTask"
      @close="selectedTask = ''"
      @changed="changed"
      @notify="notify"
    />
    <ProfileDialog
      v-if="showProfile"
      :user="user"
      @close="showProfile = false"
      @updated="user = $event"
      @logout="logout"
      @notify="notify"
    />
    <div v-if="help" class="modal-overlay" @click.self="help = false">
      <section class="help-dialog" role="dialog" aria-modal="true" aria-label="使用指南">
        <button class="icon-button dialog-close" aria-label="关闭指南" @click="help = false">
          <X :size="20" /></button
        ><h2>使用指南</h2>
        <ol class="help-steps">
          <li>
            <b>上传巡检素材</b>
            <p>选择 JPG、PNG 图片或 120 秒以内的短视频，文件最大 100 MB。</p>
          </li>
          <li>
            <b>确认轨道危险区</b>
            <p>
              清晰直线和平滑弯道可使用自动识别。道岔、遮挡或识别不稳时，沿危险区边界按顺序点击 4–12 个点。视频标定适用于固定机位。
            </p>
          </li>
          <li>
            <b>查看检测与处置</b>
            <p>检查标注截图、类别和置信度，将预警标记为处理中、已处理或误报，并填写备注。</p>
          </li>
          <li>
            <b>导出巡检报告</b>
            <p>在任务详情导出含截图的报告，打开后可打印为 PDF；预警数据可单独导出。</p>
          </li>
        </ol>
        <button class="button primary full" @click="startFromHelp">
          前往检测工作台 <ArrowUpRight :size="17" />
        </button>
      </section>
    </div>
  </div>
  <div v-if="toast" class="toast" role="status">
    <ShieldCheck :size="18" />{{ toast
    }}<button aria-label="关闭提示" @click="toast = ''"><X :size="16" /></button>
  </div>
</template>
