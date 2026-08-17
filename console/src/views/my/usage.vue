<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="header">
          <span class="title">我的用量</span>
          <span class="desc">我的每一次大模型调用消耗明细</span>
        </div>
      </template>

      <div class="toolbar">
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          style="width: 260px"
          @change="onDateChange"
        />
        <el-button type="primary" @click="loadList">查询</el-button>
      </div>

      <el-table :data="list" v-loading="loading">
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column prop="traceId" label="Trace ID" min-width="180">
          <template #default="{ row }">{{ row.traceId || '-' }}</template>
        </el-table-column>
        <el-table-column prop="model" label="模型" width="130" />
        <el-table-column prop="provider" label="供应商" width="110">
          <template #default="{ row }">{{ row.provider || '-' }}</template>
        </el-table-column>
        <el-table-column label="预估 Tokens" width="120">
          <template #default="{ row }">{{ row.estimatedTokens ? fmt(row.estimatedTokens) : '-' }}</template>
        </el-table-column>
        <el-table-column label="实际 Tokens" min-width="120">
          <template #default="{ row }">{{ row.totalTokens ? fmt(row.totalTokens) : '-' }}</template>
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
import { listMyUsages, type MyTransaction } from '@/api/my'

const loading = ref(false)
const list = ref<MyTransaction[]>([])
const total = ref(0)
const dateRange = ref<string[]>([])

const query = reactive({ page: 1, size: 10, startTime: '', endTime: '' })

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

function onDateChange() {
  if (dateRange.value && dateRange.value.length === 2) {
    query.startTime = `${dateRange.value[0]} 00:00:00`
    query.endTime = `${dateRange.value[1]} 23:59:59`
  } else {
    query.startTime = ''
    query.endTime = ''
  }
  query.page = 1
  loadList()
}

async function loadList() {
  loading.value = true
  try {
    const res = await listMyUsages(query)
    list.value = res.records as unknown as MyTransaction[]
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

.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.pagination {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
