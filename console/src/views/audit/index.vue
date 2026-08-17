<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="header">
          <div class="header-left">
            <span class="title">Audit 审计日志</span>
            <span class="desc">记录登录、资源变更、配额拦截等关键操作</span>
          </div>
          <el-button @click="loadList">刷新</el-button>
        </div>
      </template>

      <div class="toolbar">
        <el-select v-model="query.teamCode" placeholder="团队" clearable filterable style="width: 150px" @change="loadList">
          <el-option v-for="t in teams" :key="t.teamCode" :label="t.teamCode" :value="t.teamCode" />
        </el-select>
        <el-select v-model="query.eventType" placeholder="事件类型" clearable style="width: 180px" @change="loadList">
          <el-option v-for="t in eventTypes" :key="t" :label="eventText(t)" :value="t" />
        </el-select>
        <el-input v-model="query.operator" placeholder="搜索操作人" clearable style="width: 160px" @keyup.enter="loadList" @clear="loadList" />
        <el-select v-model="query.result" placeholder="结果" clearable style="width: 120px" @change="loadList">
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="FAILED" />
        </el-select>
        <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DD HH:mm:ss"
          style="width: 320px"
          @change="onTimeChange"
        />
        <el-button @click="loadList">查询</el-button>
      </div>

      <el-table :data="list" v-loading="loading">
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column prop="operator" label="操作人" width="120">
          <template #default="{ row }">{{ row.operator || 'system' }}</template>
        </el-table-column>
        <el-table-column label="事件类型" width="160">
          <template #default="{ row }">
            <el-tag :type="eventTag(row.eventType)" size="small">{{ eventText(row.eventType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="teamCode" label="团队" width="110">
          <template #default="{ row }">{{ row.teamCode || '-' }}</template>
        </el-table-column>
        <el-table-column prop="userCode" label="用户" width="120">
          <template #default="{ row }">{{ row.userCode || '-' }}</template>
        </el-table-column>
        <el-table-column prop="apiKeyId" label="API Key ID" width="150">
          <template #default="{ row }">{{ row.apiKeyId || '-' }}</template>
        </el-table-column>
        <el-table-column prop="targetCode" label="对象" width="130">
          <template #default="{ row }">{{ row.targetCode || '-' }}</template>
        </el-table-column>
        <el-table-column prop="detail" label="详情" min-width="220" show-overflow-tooltip />
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" size="small">{{ row.result === 'SUCCESS' ? '成功' : '失败' }}</el-tag>
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
import { listAudits, type AuditLog } from '@/api/audit'
import { listTeams, type Team } from '@/api/team'
import { getMetaAll } from '@/api/meta'

const loading = ref(false)
const list = ref<AuditLog[]>([])
const total = ref(0)
const teams = ref<Team[]>([])
const timeRange = ref<string[]>([])

const EVENT_MAP: Record<string, string> = {
  LOGIN_SUCCESS: '登录成功',
  LOGIN_FAILED: '登录失败',
  CREATE_TEAM: '创建团队',
  CREATE_USER: '创建用户',
  DISABLE_USER: '停用用户',
  RESET_PASSWORD: '重置密码',
  CREATE_API_KEY: '创建 API Key',
  DISABLE_API_KEY: '停用 API Key',
  DELETE_API_KEY: '删除 API Key',
  UPDATE_USER_QUOTA: '更新用户配额',
  UPDATE_TEAM_QUOTA: '更新团队配额',
  QUOTA_BLOCK: '配额拦截'
}

const eventTypes = ref<string[]>(Object.keys(EVENT_MAP))

const query = reactive({ page: 1, size: 10, teamCode: '', eventType: '', operator: '', result: '', startTime: '', endTime: '' })

function eventText(t?: string) {
  return EVENT_MAP[t || ''] || t || '-'
}

function eventTag(t?: string) {
  if (t === 'LOGIN_FAILED' || t === 'QUOTA_BLOCK') return 'danger'
  if (t === 'DISABLE_USER' || t === 'DISABLE_API_KEY' || t === 'DELETE_API_KEY' || t === 'RESET_PASSWORD') return 'warning'
  return 'info'
}

function onTimeChange() {
  if (timeRange.value && timeRange.value.length === 2) {
    query.startTime = timeRange.value[0]
    query.endTime = timeRange.value[1]
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
    const res = await listAudits(query)
    list.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    const meta = await getMetaAll()
    if (meta.auditEventTypes?.length) eventTypes.value = meta.auditEventTypes
  } catch {
    // 忽略
  }
  const teamRes = await listTeams({ page: 1, size: 100 })
  teams.value = teamRes.records
  loadList()
})
</script>

<style scoped lang="scss">
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;

  .header-left {
    display: flex;
    align-items: center;
  }

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
