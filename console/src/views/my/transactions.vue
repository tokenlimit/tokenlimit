<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="header">
          <span class="title">我的流水</span>
          <span class="desc">每次调用产生的 Token 消耗流水记录</span>
        </div>
      </template>

      <el-table :data="list" v-loading="loading">
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column prop="traceId" label="Trace ID" min-width="180">
          <template #default="{ row }">{{ row.traceId || '-' }}</template>
        </el-table-column>
        <el-table-column prop="model" label="模型" width="130" />
        <el-table-column label="消耗 Tokens" width="130">
          <template #default="{ row }">{{ row.totalTokens ? fmt(row.totalTokens) : '-' }}</template>
        </el-table-column>
        <el-table-column label="费用（元）" width="110">
          <template #default="{ row }">{{ row.cost ? Number(row.cost).toFixed(4) : '-' }}</template>
        </el-table-column>
        <el-table-column prop="consumeFrom" label="抵扣来源" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.consumeFrom === 'PERSONAL' ? 'warning' : 'success'">{{ consumeFromText(row.consumeFrom) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pagination"
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="loadList"
        @size-change="loadList"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { listMyTransactions, type MyTransaction } from '@/api/my'

const loading = ref(false)
const list = ref<MyTransaction[]>([])
const total = ref(0)

const query = reactive({ page: 1, size: 10 })

function fmt(v?: number) {
  return v == null ? '0' : Number(v).toLocaleString()
}

function consumeFromText(v?: string) {
  return v === 'PERSONAL' ? '个人' : v === 'TEAM' ? '团队' : v || '-'
}

function statusText(s?: string) {
  return { SUCCESS: '成功', FAILED: '失败', CANCELLED: '取消', PENDING: '处理中' }[s || ''] || s || '-'
}
function statusTag(s?: string) {
  return { SUCCESS: 'success', FAILED: 'danger', CANCELLED: 'info', PENDING: 'warning' }[s || ''] || 'info'
}

async function loadList() {
  loading.value = true
  try {
    const res = await listMyTransactions(query)
    list.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

onMounted(loadList)
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

.pagination {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
