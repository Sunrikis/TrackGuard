<script setup lang="ts">
defineProps<{ field: string; label: string; sort: string; direction: 'asc' | 'desc'; disabled?: boolean }>()
defineEmits<{ sortBy: [field: string] }>()
</script>
<template>
  <th :aria-sort="sort === field ? (direction === 'asc' ? 'ascending' : 'descending') : 'none'">
    <button :disabled="disabled" :aria-label="`${label}：点击${sort === field && direction === 'asc' ? '降序' : '升序'}排序`" @click="$emit('sortBy', field)">{{ label }} <span aria-hidden="true">{{ sort === field ? (direction === 'asc' ? '↑' : '↓') : '↕' }}</span></button>
  </th>
</template>
<style scoped>
button { border: 0; background: transparent; color: inherit; font: inherit; font-weight: inherit; padding: 0; cursor: pointer; white-space: nowrap; }
button:hover { color: #5579ff; }
button:disabled { cursor: wait; }
span { margin-left: 5px; color: #5579ff; }
</style>
