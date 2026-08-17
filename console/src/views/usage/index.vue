<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="header">
          <div class="header-left">
            <span class="title">Usage 用量统计</span>
            <span class="desc">查看每一次大模型调用的 Token 消耗、费用与状态</span>
          </div>
          <el-button @click="exportCsv">导出 CSV</el-button>
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
        <el-select v-model="query.teamCode" placeholder="团队" clearable filterable style="width: 150px" @change="loadList">
          <el-option v-for="t in teams" :key="t.teamCode" :label="t.teamCode" :value="t.teamCode" />
        </el-select>
        <el-select v-model="query.apiKeyId" placeholder="API Key" clearable filterable style="width: 200px" @change="loadList">
          <el-option v-for="k in apiKeys" :key="k.accessKey" :label="k.accessKey" :value="k.accessKey" />
        </el-select>
        <el-select v-model="query.model" placeholder="模型" clearable style="width: 160px" @change="loadList">
          <el-option v-for="m in models" :key="m" :label="m" :value="m" />
        </el-select>
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px" @change="loadList">
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="FAILED" />
          <el-option label="取消" value="CANCELLED" />
        </el-select>
        <el-button type="primary" @click="loadList">查询</el-button>
      </div>

      <el-table :data="list" v-loading="loading">
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column prop="teamCode" label="团队" width="110" />
        <el-table-column prop="apiKeyId" label="API Key" min-width="150">
          <template #default="{ row }">{{ row.apiKeyId || '-' }}</template>
        </el-table-column>
        <el-table-column prop="userCode" label="用户" min-width="110">
          <template #default="{ row }">{{ row.userCode || '-' }}</template>
        </el-table-column>
        <el-table-column prop="model" label="模型" width="120" />
        <el-table-column prop="provider" label="供应商" width="100">
          <template #default="{ row }">{{ row.provider || '-' }}</template>
        </el-table-column>
        <el-table-column prop="consumeFrom" label="抵扣来源" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.consumeFrom === 'PERSONAL' ? 'warning' : 'info'">{{ row.consumeFrom }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Tokens" width="110">
          <template #default="{ row }">{{ row.totalTokens ? Number(row.totalTokens).toLocaleString() : '-' }}</template>
        </el-table-column>
        <el-table-column label="费用" width="100">
          <template #default="{ row }">{{ row.cost ? `¥${Number(row.cost).toFixed(4)}` : '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
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
import { listUsages, type UsageLog } from '@/api/usage'
import { listTeams, type Team } from '@/api/team'
import { listApiKeys, type ApiKey } from '@/api/apikey'

const loading = ref(false)
const list = ref<UsageLog[]>([])
const total = ref(0)
const teams = ref<Team[]>([])
const apiKeys = ref<ApiKey[]>([])
const dateRange = ref<string[]>([])
const models = ['gpt-4o', 'gpt-4o-mini', 'claude-sonnet', 'qwen-max']

const query = reactive({ page: 1, size: 10, teamCode: '', apiKeyId: '', model: '', status: '', startTime: '', endTime: '' })

function statusText(s?: string) {
  return { SUCCESS: '成功', FAILED: '失败', CANCELLED: '取消' }[s || ''] || s || '-'
}
function statusTag(s?: string) {
  return { SUCCESS: 'success', FAILED: 'danger', CANCELLED: 'info' }[s || ''] || 'info'
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

async function loadTeams() {
  const res = await listTeams({ page: 1, size: 100 })
  teams.value = res.records
}

async function loadApiKeys() {
  const res = await listApiKeys({ page: 1, size: 100 })
  apiKeys.value = res.records
}

async function loadList() {
  loading.value = true
  try {
    const res = await listUsages(query)
    list.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function exportCsv() {
  const headers = ['时间', '团队', 'API Key', '用户', '模型', '供应商', 'Tokens', '费用', '状态']
  const rows = list.value.map((r) => [
    r.createdAt, r.teamCode, r.apiKeyId || '', r.userCode || '',
    r.model, r.provider || '', r.totalTokens || '', r.cost || '', r.status
  ])
  const csv = [headers, ...rows].map((row) => row.map((c) => `"${c}"`).join(',')).join('\n')
  const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `usage_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

onMounted(() => {
  loadTeams()
  loadApiKeys()
  loadList()
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

.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.pagination {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
