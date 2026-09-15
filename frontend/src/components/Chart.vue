<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, PieChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
echarts.use([
  LineChart,
  PieChart,
  BarChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer,
])
const props = defineProps<{ option: echarts.EChartsCoreOption; label: string }>()
const element = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined, observer: ResizeObserver | undefined
onMounted(() => {
  chart = echarts.init(element.value, {
    textStyle: { fontFamily: 'Microsoft YaHei, Segoe UI, sans-serif', fontSize: 16 },
    categoryAxis: { axisLabel: { fontSize: 15 } },
    valueAxis: { axisLabel: { fontSize: 15 } },
    legend: { textStyle: { fontSize: 16 } },
  })
  chart.setOption(props.option)
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(element.value!)
})
watch(
  () => props.option,
  (option) => chart?.setOption(option, true),
  { deep: true },
)
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>
<template><div ref="element" class="chart" role="img" :aria-label="label"></div></template>
