<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import { useVideoFrames } from '../useVideoFrames'
const props = defineProps<{ taskId: string }>()
const { image, duration, time, loading, error, open, seek, reset } = useVideoFrames()
watch(() => props.taskId, (id) => void open(id), { immediate: true })
onBeforeUnmount(reset)
</script>
<template>
  <div class="video-frame-preview" :aria-busy="loading">
    <div class="result-image-stage" v-if="image"><img :src="image" alt="原始视频选定帧" /></div>
    <div class="video-calibration-controls">
      <div class="range-heading"><span>原始视频画面</span><b>{{ time.toFixed(2) }} / {{ duration.toFixed(2) }} 秒</b></div>
      <input type="range" min="0" :max="Math.max(0, duration - 0.05)" step="0.05" :value="time"
        :disabled="!duration" aria-label="选择原始视频时间点" @input="seek" />
      <p class="field-hint" role="status">{{ loading ? '正在提取画面…' : '拖动进度条查看对应时间的静态画面' }}</p>
      <p v-if="error" class="inline-warning" role="alert">{{ error }} <button class="text-button" @click="open(taskId)">重新读取</button></p>
    </div>
  </div>
</template>
