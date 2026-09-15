<script setup lang="ts">
import { taskLabels, reviewLabels } from '../format'
import type { TaskStatus, ReviewStatus } from '../types'
defineProps<{ status: TaskStatus | ReviewStatus }>()
const labels = { ...taskLabels, ...reviewLabels }
</script>
<template>
  <span class="status-badge" :class="status.toLowerCase()"
    ><span class="status-dot"></span>{{ labels[status] }}</span
  >
</template>
