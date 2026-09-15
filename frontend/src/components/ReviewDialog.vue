<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { Check, LoaderCircle, X } from 'lucide-vue-next'
import type { WarningEvent, ReviewStatus } from '../types'
import { api, assetUrl, messageOf } from '../api'
import { reviewLabels } from '../format'
const props = defineProps<{ event: WarningEvent }>()
const emit = defineEmits<{ close: []; saved: []; notify: [message: string] }>()
const status = ref<ReviewStatus>(
    props.event.status === 'PENDING' ? 'PROCESSING' : props.event.status,
  ),
  note = ref(props.event.reviewNote || ''),
  busy = ref(false),
  error = ref('')
const closeButton = ref<HTMLButtonElement>()
async function save() {
  if (busy.value) return
  if ((status.value === 'RESOLVED' || status.value === 'FALSE_POSITIVE') && !note.value.trim()) {
    error.value = '请填写处理结果或误报原因'
    return
  }
  busy.value = true
  error.value = ''
  try {
    await api.review(props.event.id, status.value, note.value)
    emit('notify', '预警处理结果已保存')
    emit('saved')
    emit('close')
  } catch (e) {
    error.value = messageOf(e)
  } finally {
    busy.value = false
  }
}
function escape(event: KeyboardEvent) {
  if (event.key === 'Escape' && !busy.value) {
    event.stopImmediatePropagation()
    emit('close')
  }
}
onMounted(() => {
  closeButton.value?.focus()
  window.addEventListener('keydown', escape, true)
})
onBeforeUnmount(() => window.removeEventListener('keydown', escape, true))
</script>
<template>
  <div class="modal-overlay review-overlay" @click.self="!busy && $emit('close')">
    <section class="review-dialog" role="dialog" aria-modal="true" aria-labelledby="review-heading">
      <div class="panel-heading">
        <div>
          <h2 id="review-heading">预警复核与处理</h2>
        </div>
        <button
          ref="closeButton"
          class="icon-button"
          :disabled="busy"
          aria-label="关闭预警复核"
          @click="$emit('close')"
        >
          <X :size="20" />
        </button>
      </div>
      <img
        :src="assetUrl(event.taskId, event.snapshot)"
        class="review-snapshot"
        alt="当前预警的检测截图"
      />
      <div class="review-summary">
        <b>{{ event.label }} · {{ event.risk === 'HIGH' ? '高风险' : '中风险' }}</b
        ><span>置信度 {{ (event.confidence * 100).toFixed(1) }}%</span>
      </div>
      <div class="info-note">
        <small>{{ event.adviceSource === 'DEEPSEEK' ? 'DeepSeek 图像理解建议 · 请结合现场复核' : '本地规则建议 · 请结合现场复核' }}</small>
        <p>{{ event.advice }}</p>
      </div>
      <label class="field-label" for="review-status">处理状态</label
      ><select id="review-status" v-model="status" class="input">
        <option v-for="(label, key) in reviewLabels" :key="key" :value="key">
          {{ label }}
        </option></select
      ><label class="field-label" for="review-note"
        >处理备注
        <small>{{
          status === 'RESOLVED' || status === 'FALSE_POSITIVE' ? '必填' : '选填'
        }}</small></label
      ><textarea
        id="review-note"
        v-model="note"
        class="input"
        rows="3"
        maxlength="1000"
        placeholder="记录核查情况、处置措施或误报原因…"
      ></textarea>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <div class="dialog-actions">
        <button class="button secondary" :disabled="busy" @click="$emit('close')">取消</button
        ><button class="button primary" :disabled="busy" @click="save">
          <LoaderCircle v-if="busy" :size="16" class="spinning" /><Check
            v-else
            :size="16"
          />保存处理结果
        </button>
      </div>
    </section>
  </div>
</template>
