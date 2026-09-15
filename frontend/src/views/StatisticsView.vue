<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ChartNoAxesCombined, CheckCheck, Clock3, Target } from 'lucide-vue-next'
import { api, messageOf } from '../api'
import { categoryColors, categoryLabels, duration } from '../format'
import type { Category, Statistics } from '../types'
import Chart from '../components/Chart.vue'
const props = defineProps<{ refreshKey: number }>()
const emit = defineEmits<{ notify: [message: string] }>()
const days = ref(7),
  stats = ref<Statistics>(),
  error = ref('')
let sequence = 0
async function load() {
  const seq = ++sequence
  try {
    const data = await api.stats(days.value)
    if (seq === sequence) {
      stats.value = data
      error.value = ''
    }
  } catch (e) {
    if (seq === sequence) error.value = messageOf(e)
  }
}
watch([days, () => props.refreshKey], () => void load())
onMounted(load)
const categories = Object.keys(categoryLabels) as Category[]
const reviewPrecision = computed(() => {
  const s = stats.value
  return s && s.resolvedCount + s.falsePositiveCount
    ? ((s.resolvedCount / (s.resolvedCount + s.falsePositiveCount)) * 100).toFixed(1) + '%'
    : '待复核'
})
const success = computed(() => {
  const s = stats.value
  return s && s.completedTasks + s.failedTasks
    ? ((s.completedTasks / (s.completedTasks + s.failedTasks)) * 100).toFixed(1) + '%'
    : '—'
})
const pie = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: {
    bottom: 0,
    icon: 'circle',
    itemWidth: 9,
    itemHeight: 9,
    textStyle: { color: '#748096' },
  },
  series: [
    {
      name: '有效预警',
      type: 'pie',
      radius: ['54%', '75%'],
      center: ['50%', '44%'],
      label: { show: false },
      itemStyle: { borderRadius: 5, borderColor: '#fff', borderWidth: 4 },
      data: categories.map((c) => ({
        name: categoryLabels[c],
        value: stats.value?.categories.find((i) => i.category === c)?.count || 0,
        itemStyle: { color: categoryColors[c] },
      })),
    },
  ],
}))
const trend = computed(() => {
  const end = new Date((stats.value?.today || new Date().toISOString().slice(0, 10)) + 'T00:00:00Z')
  const dates = Array.from({ length: days.value }, (_, i) => {
    const d = new Date(end)
    d.setUTCDate(end.getUTCDate() - days.value + 1 + i)
    return d.toISOString().slice(0, 10)
  })
  return {
    grid: { left: 40, right: 20, top: 20, bottom: 32 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: dates.map((d) => d.slice(5)),
      axisLine: { lineStyle: { color: '#e7edf4' } },
      axisTick: { show: false },
      axisLabel: { color: '#8c97a7' },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#edf0f5', type: 'dashed' } },
      axisLabel: { color: '#8c97a7' },
    },
    series: [
      {
        name: '有效预警',
        type: 'bar',
        barMaxWidth: 30,
        itemStyle: { color: '#6b84f4', borderRadius: [5, 5, 0, 0] },
        data: dates.map((d) => stats.value?.trend.find((i) => i.date === d)?.count || 0),
      },
    ],
  }
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>统计分析<span class="heading-dot">.</span></h1>
      <p>按时间、类别和处理状态查看预警。</p>
    </div>
    <div class="segmented period-switch">
      <button v-for="n in [7, 30, 90]" :key="n" :class="{ active: days === n }" @click="days = n">
        近 {{ n }} 天
      </button>
    </div>
  </div>
  <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
  <div class="metric-grid">
    <article class="metric-card">
      <div class="metric-top">
        <span>有效预警总数</span
        ><span class="metric-icon blue"><ChartNoAxesCombined :size="20" /></span>
      </div>
      <strong>{{ stats?.warningCount ?? '—' }}<small>条</small></strong>
      <p>范围内目标事件，排除误报</p>
    </article>
    <article class="metric-card">
      <div class="metric-top">
        <span>人工复核有效率</span><span class="metric-icon green"><Target :size="20" /></span>
      </div>
      <strong>{{ reviewPrecision }}</strong>
      <p>已处理 /（已处理 + 误报）</p>
    </article>
    <article class="metric-card">
      <div class="metric-top">
        <span>平均任务耗时</span><span class="metric-icon orange"><Clock3 :size="20" /></span>
      </div>
      <strong>{{ stats?.completedTasks ? duration(stats.avgElapsedMs) : '—' }}</strong>
      <p>包含模型加载与结果保存</p>
    </article>
    <article class="metric-card">
      <div class="metric-top">
        <span>任务执行成功率</span><span class="metric-icon purple"><CheckCheck :size="20" /></span>
      </div>
      <strong>{{ success }}</strong>
      <p>已取消与执行中任务不计入</p>
    </article>
  </div>
  <div class="dashboard-charts stats-charts">
    <section class="panel">
      <div class="panel-heading">
        <div>
          <h3>预警数量趋势</h3>
          <p>按北京时间自然日汇总</p>
        </div>
        <span class="tiny-label">近 {{ days }} 天</span>
      </div>
      <Chart :option="trend" :label="`近${days}天的有效预警统计`" />
    </section>
    <section class="panel">
      <div class="panel-heading">
        <div>
          <h3>风险类型构成</h3>
          <p>五类轨道异物的分布</p>
        </div>
      </div>
      <div class="donut-wrap">
        <Chart :option="pie" label="人员、车辆、摩托车、动物、岩石与障碍物的预警占比" />
        <div class="donut-center">
          <b>{{ stats?.warningCount || 0 }}</b
          ><span>有效预警</span>
        </div>
      </div>
    </section>
  </div>
  <section class="panel">
    <div class="panel-heading">
      <div>
        <h3>复核与处置概况</h3>
        <p>汇总当前记录的处理状态</p>
      </div>
    </div>
    <div class="review-overview">
      <div>
        <span class="review-status-icon green"><CheckCheck :size="24" /></span
        ><b>{{ stats?.resolvedCount ?? '—' }}</b
        ><span>已处理预警</span>
      </div>
      <div>
        <span class="review-status-icon orange"><Clock3 :size="24" /></span
        ><b>{{ stats?.pendingCount ?? '—' }}</b
        ><span>全量待跟进预警</span>
      </div>
      <div>
        <span class="review-status-icon purple"><Target :size="24" /></span
        ><b>{{ stats?.falsePositiveCount ?? '—' }}</b
        ><span>人工确认误报</span>
      </div>
    </div>
  </section>
</template>
