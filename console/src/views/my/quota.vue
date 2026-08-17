<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="header">
          <span class="title">我的额度</span>
          <span class="desc">个人与团队维度的配额规则明细</span>
        </div>
      </template>

      <el-table :data="list" v-loading="loading">
        <el-table-column prop="targetType" label="对象类型" width="120">
          <template #default="{ row }">
            <el-tag :type="row.targetType === 'USER' ? 'warning' : 'success'" size="small">{{ row.targetType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="targetCode" label="对象编码" width="160" />
        <el-table-column prop="model" label="模型" width="140">
          <template #default="{ row }">{{ row.model || '全部模型' }}</template>
        </el-table-column>
        <el-table-column prop="limitType" label="限制类型" width="110">
          <template #default="{ row }">{{ row.limitType === 'TOKEN' ? 'Token' : row.limitType }}</template>
        </el-table-column>
        <el-table-column prop="limitValue" label="额度上限" min-width="120">
          <template #default="{ row }">{{ fmt(row.limitValue) }}</template>
        </el-table-column>
        <el-table-column prop="used" label="已使用" min-width="120">
          <template #default="{ row }">{{ fmt(row.used) }}</template>
        </el-table-column>
        <el-table-column prop="remain" label="剩余" min-width="120">
          <template #default="{ row }">{{ fmt(row.remain) }}</template>
        </el-table-column>
        <el-table-column prop="period" label="统计周期" width="120">
          <template #default="{ row }">{{ periodText(row.period) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getMyQuota, type MyQuotaItem } from '@/api/my'

const loading = ref(false)
const list = ref<MyQuotaItem[]>([])

function fmt(v?: number) {
  return v == null ? '0' : Number(v).toLocaleString()
}

function periodText(p?: string) {
  const map: Record<string, string> = { MINUTE: '分', HOUR: '时', DAY: '日', WEEK: '周', MONTH: '月', YEAR: '年', TOTAL: '累计' }
  return map[p || ''] || p || '-'
}

onMounted(async () => {
  loading.value = true
  try {
    list.value = await getMyQuota()
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
