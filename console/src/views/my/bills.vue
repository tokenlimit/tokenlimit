<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="header">
          <span class="title">我的账单</span>
          <span class="desc">按日聚合的用量与费用账单</span>
        </div>
      </template>

      <el-table :data="list" v-loading="loading">
        <el-table-column prop="period" label="账期" width="160" />
        <el-table-column label="调用次数" width="130">
          <template #default="{ row }">{{ fmt(row.callCount) }}</template>
        </el-table-column>
        <el-table-column label="消耗 Tokens" min-width="160">
          <template #default="{ row }">{{ fmt(row.totalTokens) }}</template>
        </el-table-column>
        <el-table-column label="费用（元）" min-width="140">
          <template #default="{ row }">{{ Number(row.totalCost || 0).toFixed(4) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listMyBills, type MyBill } from '@/api/my'

const loading = ref(false)
const list = ref<MyBill[]>([])

function fmt(v?: number) {
  return v == null ? '0' : Number(v).toLocaleString()
}

onMounted(async () => {
  loading.value = true
  try {
    list.value = await listMyBills()
  } finally {
    loading.value = false
  }
})
</script>

<style scoped lang="scss">
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;

  .title {
    font-size: 16px;
    font-weight: 600;
  }

  .desc {
    margin-left: 12px;
    color: #909399;
    font-size: 13px;
  }
}
</style>
