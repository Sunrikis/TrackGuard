<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { Trash2, LoaderCircle } from 'lucide-vue-next'
import { api, messageOf } from '../api'
const props = defineProps<{ kind: 'task' | 'event'; id?: string; ids?: string[]; title: string }>()
const emit = defineEmits<{ close: []; deleted: []; notify: [message: string] }>()
const busy = ref(false), error = ref(''), cancelButton = ref<HTMLButtonElement>()
async function remove() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    if (props.ids) {
      const result = props.kind === 'task' ? await api.deleteTasks(props.ids) : await api.deleteEvents(props.ids)
      emit('notify', `已删除 ${result.deletedCount} ${props.kind === 'task' ? '项任务及关联预警、日志' : '条预警'}`)
    } else if (props.id) {
      if (props.kind === 'task') await api.deleteTask(props.id)
      else await api.deleteEvent(props.id)
      emit('notify', props.kind === 'task' ? '任务及关联预警、日志已删除' : '预警记录已删除')
    } else throw new Error('未选择待删除记录')
    emit('deleted')
    emit('close')
  } catch (e) {
    error.value = messageOf(e)
  } finally { busy.value = false }
}
function keyboard(event: KeyboardEvent) {
  if (event.key === 'Escape' && !busy.value) { event.stopImmediatePropagation(); emit('close') }
  if (event.key === 'Tab') {
    const dialog = cancelButton.value?.closest('section')
    const buttons = dialog?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')
    if (!buttons?.length) return
    const first = buttons[0], last = buttons[buttons.length - 1]
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
  }
}
let previousFocus: HTMLElement | null = null
onMounted(() => { previousFocus = document.activeElement as HTMLElement; cancelButton.value?.focus(); window.addEventListener('keydown', keyboard, true) })
onBeforeUnmount(() => { window.removeEventListener('keydown', keyboard, true); if (previousFocus?.isConnected) previousFocus.focus() })
</script>
<template>
  <div class="modal-overlay review-overlay" @click.self="!busy && $emit('close')">
    <section class="review-dialog" role="alertdialog" aria-modal="true" aria-labelledby="delete-heading" aria-describedby="delete-description" :aria-busy="busy">
      <div class="panel-heading"><h2 id="delete-heading">{{ ids ? `批量删除 ${ids.length} 条记录` : `删除${kind === 'task' ? '检测任务' : '预警记录'}` }}？</h2><Trash2 :size="24" class="text-danger" /></div>
      <p class="delete-record-title">{{ title }}</p>
      <p v-if="ids" class="delete-description">批量删除按一次事务执行；任一记录状态已变化时，本次删除不会生效。</p>
      <p id="delete-description" class="delete-description">{{ kind === 'task' ? '任务、关联预警、处理备注和日志将永久删除。' : '此条预警将永久删除，来源任务和原始检测结果保留。' }}磁盘素材与截图不受影响。</p>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <div class="dialog-actions">
        <button ref="cancelButton" class="button secondary" :disabled="busy" @click="$emit('close')">取消</button>
        <button class="button danger" :disabled="busy" @click="remove"><LoaderCircle v-if="busy" :size="16" class="spinning" /><Trash2 v-else :size="16" />{{ busy ? '删除中…' : '确认删除' }}</button>
      </div>
    </section>
  </div>
</template>
<style scoped>
.delete-record-title { font-weight: 600; overflow-wrap: anywhere; margin: 16px 0; }
.delete-description { line-height: 1.8; color: var(--text-secondary, #667085); }
</style>
