<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Camera, LoaderCircle, LogOut, UserRound, X } from 'lucide-vue-next'
import { api, messageOf } from '../api'
import type { UserProfile } from '../types'
defineProps<{ user: UserProfile }>()
const emit = defineEmits<{
  close: []
  updated: [user: UserProfile]
  logout: []
  notify: [message: string]
}>()
const input = ref<HTMLInputElement>(),
  closeButton = ref<HTMLButtonElement>(),
  busy = ref(false),
  error = ref('')
async function upload(event: Event) {
  const el = event.target as HTMLInputElement,
    file = el.files?.[0]
  el.value = ''
  if (!file) return
  if (
    !['image/jpeg', 'image/png'].includes(file.type) ||
    !file.size ||
    file.size > 2 * 1024 * 1024
  ) {
    error.value = '请选择不超过 2 MB 的 JPG 或 PNG 图片'
    return
  }
  busy.value = true
  error.value = ''
  try {
    emit('updated', await api.avatar(file))
    emit('notify', '头像已保存')
  } catch (e) {
    error.value = messageOf(e)
  } finally {
    busy.value = false
  }
}
function keydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && !busy.value) emit('close')
}
onMounted(() => {
  closeButton.value?.focus()
  window.addEventListener('keydown', keydown)
})
onBeforeUnmount(() => window.removeEventListener('keydown', keydown))
</script>
<template>
  <div class="modal-overlay" @click.self="!busy && $emit('close')">
    <section class="profile-dialog" role="dialog" aria-modal="true" aria-labelledby="profile-title">
      <button
        ref="closeButton"
        class="icon-button dialog-close"
        aria-label="关闭个人资料"
        :disabled="busy"
        @click="$emit('close')"
      >
        <X :size="22" />
      </button>
      <h2 id="profile-title">个人资料</h2>
      <input ref="input" type="file" accept="image/jpeg,image/png" hidden @change="upload" />
      <button
        class="avatar-upload profile-avatar"
        :disabled="busy"
        aria-label="更换头像"
        @click="input?.click()"
      >
        <img v-if="user.avatarUrl" :src="user.avatarUrl" alt="当前头像" /><UserRound
          v-else
          :size="44"
        /><span
          ><LoaderCircle v-if="busy" :size="16" class="spinning" /><Camera v-else :size="16"
        /></span>
      </button>
      <h3>{{ user.displayName }}</h3>
      <p class="profile-username">@{{ user.username }}</p>
      <button class="button secondary full" :disabled="busy" @click="input?.click()">
        <Camera :size="18" />{{ busy ? '正在保存头像…' : '上传新头像' }}
      </button>
      <p class="field-hint">支持 JPG、PNG，最大 2 MB；图片会自动裁剪为正方形。</p>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <button class="button full profile-logout" :disabled="busy" @click="$emit('logout')">
        <LogOut :size="18" />退出登录
      </button>
    </section>
  </div>
</template>
