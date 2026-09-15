<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import {
  ArrowRight,
  Camera,
  Eye,
  EyeOff,
  LoaderCircle,
  LockKeyhole,
  ScanLine,
  ShieldCheck,
  TrainFront,
  UserRound,
} from 'lucide-vue-next'
import { api, messageOf } from '../api'
import type { UserProfile } from '../types'
defineProps<{ ready: boolean; connectionError: string }>()
const emit = defineEmits<{
  authenticated: [user: UserProfile]
  notify: [message: string]
  reconnect: []
}>()
const mode = ref<'login' | 'register'>('login')
const username = ref(''),
  password = ref(''),
  confirmPassword = ref(''),
  displayName = ref('')
const showPassword = ref(false),
  busy = ref(false),
  error = ref('')
const avatar = ref<File>(),
  avatarPreview = ref(''),
  avatarInput = ref<HTMLInputElement>()
function switchMode(value: 'login' | 'register') {
  mode.value = value
  error.value = ''
  password.value = ''
  confirmPassword.value = ''
}
function selectAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (
    !['image/jpeg', 'image/png'].includes(file.type) ||
    !file.size ||
    file.size > 2 * 1024 * 1024
  ) {
    error.value = '头像请选择不超过 2 MB 的 JPG 或 PNG 图片'
    return
  }
  if (avatarPreview.value) URL.revokeObjectURL(avatarPreview.value)
  avatar.value = file
  avatarPreview.value = URL.createObjectURL(file)
  error.value = ''
}
async function submit() {
  if (busy.value) return
  error.value = ''
  if (mode.value === 'register' && password.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  busy.value = true
  try {
    const session =
      mode.value === 'login'
        ? await api.login(username.value, password.value)
        : await api.register(username.value, password.value, displayName.value)
    let user = session.user!
    if (mode.value === 'register' && avatar.value) {
      try {
        user = await api.avatar(avatar.value)
      } catch (e) {
        emit('notify', `账号已创建，头像未保存：${messageOf(e)}。可在个人资料中重试。`)
      }
    }
    password.value = ''
    confirmPassword.value = ''
    emit('authenticated', user)
  } catch (e) {
    error.value = messageOf(e)
  } finally {
    busy.value = false
  }
}
onBeforeUnmount(() => {
  if (avatarPreview.value) URL.revokeObjectURL(avatarPreview.value)
})
</script>
<template>
  <main class="auth-page">
    <section class="auth-story" aria-label="轨安巡检平台">
      <div class="auth-brand">
        <span class="brand-mark"><TrainFront :size="28" /></span><span>轨安 <b>TrackGuard</b></span>
      </div>
      <div class="auth-story-content">
        <span class="auth-eyebrow"><span></span> TRACKGUARD</span>
        <h1>轨道异物检测<br />与预警记录</h1>
        <p>检测图片和短视频中的目标，<br />记录危险区预警与人工复核结果。</p>
        <div class="auth-rail-scene" aria-hidden="true">
          <svg viewBox="0 0 540 280" fill="none">
            <defs>
              <linearGradient
                id="rail-area"
                x1="270"
                y1="30"
                x2="270"
                y2="270"
                gradientUnits="userSpaceOnUse"
              >
                <stop stop-color="#7796ff" stop-opacity="0" />
                <stop offset="1" stop-color="#7796ff" stop-opacity=".22" />
              </linearGradient>
            </defs>
            <path d="M250 35 102 268H438L290 35Z" fill="url(#rail-area)" />
            <path d="m250 35-148 233M290 35l148 233" stroke="#85a1ff" stroke-width="3" />
            <path
              d="M236 64h68M224 86h92M204 116h132M179 153h182M151 198h238M113 254h314"
              stroke="#50658b"
              stroke-width="5"
            />
            <path d="M264 35 216 268M276 35l48 233" stroke="#c9d5ff" stroke-opacity=".35" />
            <rect
              x="211"
              y="107"
              width="46"
              height="81"
              rx="4"
              stroke="#9aeece"
              stroke-width="2"
              stroke-dasharray="8 5"
            />
            <circle cx="234" cy="129" r="7" fill="#9aeece" />
            <path d="M225 144h18l5 19h-9v17h-10v-17h-9Z" fill="#9aeece" />
            <circle cx="403" cy="59" r="5" fill="#9aeece" />
            <path d="M412 59h32" stroke="#9aeece" stroke-opacity=".5" />
          </svg>
          <span class="scene-tag"><ScanLine :size="16" /> 影像目标检测</span>
          <span class="scene-status"><ShieldCheck :size="16" /> 预警与复核记录</span>
        </div>
        <div class="auth-features">
          <span><ScanLine :size="18" />多模型检测</span
          ><span><ShieldCheck :size="18" />危险区预警</span>
        </div>
      </div>
      <p class="auth-copyright">轨道异物检测与预警系统</p>
    </section>
    <section class="auth-form-side">
      <form class="auth-card" @submit.prevent="submit">
        <h2>
          {{ mode === 'login' ? '登录' : '创建账号' }}<span class="heading-dot">.</span>
        </h2>
        <p class="auth-intro">
          {{
            mode === 'login'
              ? '登录后查看检测任务和预警记录。'
              : '创建账号后即可使用检测与复核功能。'
          }}
        </p>
        <div class="auth-tabs" role="tablist" aria-label="账号入口">
          <button
            type="button"
            role="tab"
            :aria-selected="mode === 'login'"
            :class="{ active: mode === 'login' }"
            :disabled="busy"
            @click="switchMode('login')"
          >
            登录账号</button
          ><button
            type="button"
            role="tab"
            :aria-selected="mode === 'register'"
            :class="{ active: mode === 'register' }"
            :disabled="busy"
            @click="switchMode('register')"
          >
            注册账号
          </button>
        </div>
        <div v-if="mode === 'register'" class="register-avatar">
          <input
            ref="avatarInput"
            type="file"
            accept="image/jpeg,image/png"
            hidden
            @change="selectAvatar"
          />
          <button
            type="button"
            class="avatar-upload"
            :disabled="busy"
            aria-label="上传注册头像"
            @click="avatarInput?.click()"
          >
            <img v-if="avatarPreview" :src="avatarPreview" alt="注册头像预览" /><UserRound
              v-else
              :size="30"
            /><span><Camera :size="14" /></span>
          </button>
          <div>
            <b>设置个人头像 <small>选填</small></b>
            <p>JPG / PNG，最大 2 MB</p>
          </div>
        </div>
        <label for="auth-username" class="field-label">用户名</label>
        <div class="auth-input">
          <UserRound :size="19" /><input
            id="auth-username"
            v-model="username"
            required
            minlength="3"
            maxlength="32"
            pattern="[A-Za-z0-9_]{3,32}"
            autocomplete="username"
            placeholder="3–32 位字母、数字或下划线"
            :disabled="busy"
          />
        </div>
        <template v-if="mode === 'register'"
          ><label for="auth-display" class="field-label">昵称</label
          ><input
            id="auth-display"
            v-model="displayName"
            class="input"
            maxlength="40"
            autocomplete="nickname"
            placeholder="你的巡检显示名称（选填）"
            :disabled="busy"
        /></template>
        <label for="auth-password" class="field-label">密码</label>
        <div class="auth-input">
          <LockKeyhole :size="19" /><input
            id="auth-password"
            v-model="password"
            :type="showPassword ? 'text' : 'password'"
            required
            minlength="8"
            maxlength="128"
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
            placeholder="请输入至少 8 位密码"
            :disabled="busy"
          /><button
            type="button"
            class="icon-button"
            :aria-label="showPassword ? '隐藏密码' : '显示密码'"
            @click="showPassword = !showPassword"
          >
            <EyeOff v-if="showPassword" :size="18" /><Eye v-else :size="18" />
          </button>
        </div>
        <template v-if="mode === 'register'"
          ><label for="auth-confirm" class="field-label">确认密码</label
          ><input
            id="auth-confirm"
            v-model="confirmPassword"
            class="input"
            :type="showPassword ? 'text' : 'password'"
            required
            minlength="8"
            maxlength="128"
            autocomplete="new-password"
            placeholder="再次输入密码"
            :disabled="busy"
        /></template>
        <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
        <div v-if="connectionError" class="inline-error" role="alert">
          {{ connectionError
          }}<button type="button" class="text-button" @click="$emit('reconnect')">重新连接</button>
        </div>
        <button class="button primary full auth-submit" :disabled="busy || !ready">
          <LoaderCircle v-if="busy || !ready" :size="20" class="spinning" />{{
            !ready
              ? '正在连接服务…'
              : busy
                ? '正在处理…'
                : mode === 'login'
                  ? '登录工作空间'
                  : '注册并进入工作空间'
          }}<ArrowRight v-if="!busy && ready" :size="20" />
        </button>
        <p class="auth-footnote">
          <ShieldCheck :size="16" />账号独立登录 · 巡检记录在工作空间内共享
        </p>
      </form>
    </section>
  </main>
</template>
