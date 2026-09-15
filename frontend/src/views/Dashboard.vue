<script setup lang="ts">
import { computed } from 'vue'
import {
  ArrowRight,
  ArrowUpRight,
  CheckCheck,
  CircleCheck,
  Clock3,
  FileSearch,
  FolderOpen,
  Plus,
  ScanLine,
  ShieldCheck,
  TriangleAlert,
  Users,
  Car,
  Bike,
  PawPrint,
  Package,
} from 'lucide-vue-next'
import type { Statistics, Task, WarningEvent, Page, Category } from '../types'
import { categoryColors, categoryLabels, dateTime } from '../format'
import Chart from '../components/Chart.vue'
import StatusBadge from '../components/StatusBadge.vue'
defineEmits<{ navigate: [page: Page]; openTask: [id: string]; warnings: [] }>()
const props = defineProps<{
  stats?: Statistics
  tasks: Task[]
  events: WarningEvent[]
  loading: boolean
}>()
const categories = ['person', 'vehicle', 'motorcycle', 'animal', 'obstacle'] as Category[]
const icons = { person: Users, vehicle: Car, motorcycle: Bike, animal: PawPrint, obstacle: Package }
const number = (value?: number) => (value == null ? '—' : value.toLocaleString('zh-CN'))
const completeRate = computed(() => {
  const s = props.stats
  return s && s.completedTasks + s.failedTasks
    ? `${Math.round((s.completedTasks / (s.completedTasks + s.failedTasks)) * 100)}%`
    : '—'
})
const trendOption = computed(() => {
  const end = new Date((props.stats?.today || new Date().toISOString().slice(0, 10)) + 'T00:00:00Z')
  const days = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(end)
    d.setUTCDate(end.getUTCDate() - 6 + i)
    return d.toISOString().slice(0, 10)
  })
  return {
    animationDuration: 450,
    grid: { left: 36, right: 14, top: 20, bottom: 30 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#fff',
      borderColor: '#e7ebf3',
      textStyle: { color: '#334155' },
    },
    xAxis: {
      type: 'category',
      data: days.map((d) => d.slice(5).replace('-', '/')),
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#e8edf4' } },
      axisTick: { show: false },
      axisLabel: { color: '#96a0b1', margin: 14 },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      min: 0,
      splitLine: { lineStyle: { color: '#edf0f5', type: 'dashed' } },
      axisLabel: { color: '#96a0b1' },
    },
    series: [
      {
        name: '有效预警',
        type: 'line',
        smooth: 0.3,
        symbolSize: 7,
        showSymbol: false,
        itemStyle: { color: '#5b78f5' },
        lineStyle: { width: 3 },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: '#6885fa38' },
              { offset: 1, color: '#6885fa00' },
            ],
          },
        },
        data: days.map((d) => props.stats?.trend.find((t) => t.date === d)?.count || 0),
      },
    ],
  }
})
const pending = computed(() =>
  props.events.filter((e) => e.status === 'PENDING' || e.status === 'PROCESSING').slice(0, 3),
)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>巡检总览<span class="heading-dot">.</span></h1>
      <p>查看任务、预警和处理进度。</p>
    </div>
    <button class="button primary" @click="$emit('navigate', 'detect')">
      <Plus :size="18" />新建检测
    </button>
  </div>
  <section class="hero-panel">
    <div class="hero-content">
      <span class="hero-kicker"><span></span> 影像检测与预警</span>
      <h2>检测轨道区域内的目标</h2>
      <p>
        上传图片或短视频，设置轨道危险区，<br class="desktop-break" />保存检测截图与处理记录。
      </p>
      <button class="hero-link" @click="$emit('navigate', 'detect')">
        进入检测工作台 <ArrowRight :size="18" />
      </button>
    </div>
    <div class="hero-visual" aria-hidden="true">
      <div class="orbit orbit-one"></div>
      <div class="orbit orbit-two"></div>
      <div class="hero-cross cross-one">+</div>
      <div class="hero-cross cross-two">+</div>
      <svg viewBox="0 0 480 270">
        <defs>
          <linearGradient id="rail-fill" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stop-color="#738efe" stop-opacity=".16" />
            <stop offset="1" stop-color="#839bff" stop-opacity=".015" />
          </linearGradient>
        </defs>
        <path d="M255 24 103 260 385 260 293 24Z" fill="url(#rail-fill)" />
        <g stroke="#a9baf8" stroke-width="2" fill="none">
          <path d="M267 25 160 265M285 25 329 265" />
          <path
            d="M255 62 292 62M244 88 298 88M232 116 304 116M218 148 310 148M201 184 318 184M181 226 325 226"
            stroke-width="7"
            opacity=".5"
          />
        </g>
        <path
          d="M248 30 85 264M301 30 403 264"
          stroke="#97adef"
          stroke-dasharray="4 8"
          fill="none"
        />
        <rect
          x="208"
          y="100"
          width="90"
          height="113"
          rx="6"
          stroke="#5e7eff"
          stroke-width="2"
          fill="#607eff0c"
        />
        <path
          d="M208 117v-17h17M282 100h16v17M298 196v17h-17M224 213h-16v-17"
          stroke="#5879ff"
          stroke-width="4"
          fill="none"
        />
        <circle cx="253" cy="132" r="12" fill="#7991ee" />
        <path
          d="M234 161q19-18 38 0l-5 22h-27zM245 181l-5 20M261 181l5 20"
          stroke="#7991ee"
          stroke-width="9"
          stroke-linecap="round"
          fill="#7991ee"
        />
      </svg>
      <div class="hero-float">
        <span class="float-icon"><ShieldCheck :size="20" /></span
        ><span><b>轨道危险区识别</b><small>为每次预警提供判断依据</small></span
        ><span class="float-check"><CircleCheck :size="16" /></span>
      </div>
      <span class="hero-caption">RAILWAY INSPECTION</span>
    </div>
  </section>
  <div class="metric-grid">
    <article class="metric-card">
      <div class="metric-top">
        <span>累计检测任务</span><span class="metric-icon blue"><ScanLine :size="20" /></span>
      </div>
      <strong>{{ number(stats?.totalTasks) }}<small>次</small></strong>
      <p><span class="subtle-dot blue-dot"></span>近 7 天的巡检记录</p>
    </article>
    <article class="metric-card">
      <div class="metric-top">
        <span>发现有效预警</span
        ><span class="metric-icon orange"><TriangleAlert :size="20" /></span>
      </div>
      <strong>{{ number(stats?.warningCount) }}<small>条</small></strong>
      <p><span class="subtle-dot orange-dot"></span>已排除人工标记的误报</p>
    </article>
    <article class="metric-card actionable" @click="$emit('warnings')">
      <div class="metric-top">
        <span>待跟进预警</span><span class="metric-icon red"><Clock3 :size="20" /></span>
      </div>
      <strong>{{ number(stats?.pendingCount) }}<small>条</small></strong>
      <p class="metric-action">查看待处理事项 <ArrowUpRight :size="14" /></p>
    </article>
    <article class="metric-card">
      <div class="metric-top">
        <span>任务完成率</span><span class="metric-icon green"><CheckCheck :size="20" /></span>
      </div>
      <strong>{{ completeRate }}</strong>
      <p><span class="subtle-dot green-dot"></span>基于已完成和失败任务</p>
    </article>
  </div>
  <div class="dashboard-charts">
    <section class="panel">
      <div class="panel-heading">
        <div>
          <h3>预警趋势 <span class="live-label">近 7 天</span></h3>
          <p>按目标关联去重后的有效预警</p>
        </div>
        <button class="text-button" @click="$emit('navigate', 'statistics')">
          统计分析 <ArrowUpRight :size="15" />
        </button>
      </div>
      <Chart :option="trendOption" label="近七天有效预警数量趋势" />
      <div v-if="!stats?.warningCount" class="chart-empty-caption">
        暂无有效预警，完成检测后将在这里呈现
      </div>
    </section>
    <section class="panel">
      <div class="panel-heading">
        <div>
          <h3>异物类型分布</h3>
          <p>按有效预警类别统计</p>
        </div>
        <span class="tiny-label">5 类目标</span>
      </div>
      <div class="category-bars">
        <div v-for="category in categories" :key="category" class="category-row">
          <span
            class="category-icon"
            :style="{
              color: categoryColors[category],
              background: categoryColors[category] + '14',
            }"
            ><component :is="icons[category]" :size="18"
          /></span>
          <div>
            <div class="category-label">
              <span>{{ categoryLabels[category] }}</span
              ><b
                >{{ stats?.categories.find((c) => c.category === category)?.count || 0 }}
                <small>次</small></b
              >
            </div>
            <div class="bar-track">
              <div
                :style="{
                  width: `${((stats?.categories.find((c) => c.category === category)?.count || 0) / Math.max(stats?.warningCount || 0, 1)) * 100}%`,
                  background: categoryColors[category],
                }"
              ></div>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
  <section class="panel recent-panel">
    <div class="panel-heading">
      <div>
        <h3>最近检测任务</h3>
        <p>最近创建的检测任务</p>
      </div>
      <button class="text-button" @click="$emit('navigate', 'records')">
        查看全部 <ArrowRight :size="15" />
      </button>
    </div>
    <div v-if="tasks.length" class="table-scroll">
      <table class="data-table">
        <thead>
          <tr>
            <th>任务名称</th>
            <th>检测时间</th>
            <th>素材类型</th>
            <th>任务状态</th>
            <th>预警数量</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="task in tasks" :key="task.id">
            <td>
              <button class="task-link" @click="$emit('openTask', task.id)">
                <span class="file-icon"><FileSearch :size="19" /></span
                ><span
                  ><b>{{ task.title }}</b
                  ><small>{{ task.id.slice(0, 8).toUpperCase() }}</small></span
                >
              </button>
            </td>
            <td>{{ dateTime(task.createdAt) }}</td>
            <td>{{ task.mediaType === 'IMAGE' ? '图片' : '短视频' }}</td>
            <td><StatusBadge :status="task.status" /></td>
            <td>
              <span :class="{ 'text-danger': task.eventCount > 0 }">{{ task.eventCount }}</span>
              <span class="muted">条</span>
            </td>
            <td>
              <button class="table-action" @click="$emit('openTask', task.id)">
                查看 <ArrowUpRight :size="14" />
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="empty-state compact">
      <span class="empty-icon"><FolderOpen :size="28" /></span>
      <h4>{{ loading ? '正在加载巡检记录…' : '从第一次检测开始' }}</h4>
      <p>上传轨道图片或短视频，建立你的巡检记录。</p>
      <button class="button secondary" @click="$emit('navigate', 'detect')">
        <Plus :size="16" />新建检测
      </button>
    </div>
  </section>
  <div v-if="pending.length" class="pending-strip">
    <span class="pending-strip-icon"><TriangleAlert :size="19" /></span>
    <div>
      <b>还有 {{ stats?.pendingCount }} 条预警等待跟进</b>
      <p>{{ pending[0].taskTitle }} · {{ pending[0].label }}进入危险区域</p>
    </div>
    <button class="text-button" @click="$emit('warnings')">
      前往处理 <ArrowRight :size="16" />
    </button>
  </div>
</template>
