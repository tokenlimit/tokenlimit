<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="header">
          <div class="header-left">
            <span class="title">API Key 管理</span>
            <span class="desc">API Key 强绑定团队 / 用户</span>
          </div>
          <el-button type="primary" @click="openDialog()">创建 API Key</el-button>
        </div>
      </template>

      <div class="toolbar">
        <el-select v-model="query.teamCode" placeholder="团队" clearable filterable style="width: 150px" @change="onTeamChange">
          <el-option v-for="t in teams" :key="t.teamCode" :label="t.teamCode" :value="t.teamCode" />
        </el-select>
        <el-select v-model="query.userCode" placeholder="用户" clearable filterable style="width: 170px" @change="loadList">
          <el-option v-for="u in users" :key="u.userCode" :label="`${u.userName} (${u.userCode})`" :value="u.userCode" />
        </el-select>
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px" @change="loadList">
          <el-option v-for="s in apiKeyStatuses" :key="s" :label="s" :value="s" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="搜索 AccessKey / Key 名称 / KeyId" clearable style="width: 220px" @keyup.enter="loadList" @clear="loadList" />
        <el-button @click="loadList">查询</el-button>
      </div>

      <el-table :data="list" v-loading="loading">
        <el-table-column prop="keyName" label="Key 名称" width="150">
          <template #default="{ row }">{{ row.keyName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="accessKey" label="AccessKey" min-width="190" />
        <el-table-column prop="keyId" label="KeyId" min-width="150">
          <template #default="{ row }"><code>{{ row.keyId }}</code></template>
        </el-table-column>
        <el-table-column prop="teamCode" label="团队" width="110" />
        <el-table-column prop="userCode" label="绑定用户" width="120">
          <template #default="{ row }">
            <el-tag size="small">{{ row.userCode }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="expireAt" label="过期时间" width="165">
          <template #default="{ row }">{{ row.expireAt || '永不过期' }}</template>
        </el-table-column>
        <el-table-column prop="lastUsedAt" label="最后使用" width="165">
          <template #default="{ row }">{{ row.lastUsedAt || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="95">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button link type="warning" @click="handleResetSecret(row)">重置密钥</el-button>
            <el-button link :type="row.status === 'ENABLED' ? 'warning' : 'success'" @click="handleToggleStatus(row)">
              {{ row.status === 'ENABLED' ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑 API Key' : '创建 API Key'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="团队" prop="teamCode">
          <el-select v-model="form.teamCode" placeholder="选择团队" filterable style="width: 100%" :disabled="!!form.id" @change="onFormTeamChange">
            <el-option v-for="t in teams" :key="t.teamCode" :label="t.teamCode" :value="t.teamCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="绑定用户" prop="userCode">
          <el-select v-model="form.userCode" placeholder="选择用户（必选）" filterable style="width: 100%" :disabled="!!form.id">
            <el-option v-for="u in formUsers" :key="u.userCode" :label="`${u.userName} (${u.userCode})`" :value="u.userCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="Key 名称" prop="keyName">
          <el-input v-model="form.keyName" placeholder="便于识别的名称" maxlength="50" />
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker v-model="form.expireAt" type="datetime" placeholder="可空（永不过期）" value-format="YYYY-MM-DD HH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="模型白名单">
          <el-input v-model="form.allowedModels" placeholder="逗号分隔的模型列表，如 gpt-4o,gpt-4o-mini；空表示全部（PRD 10.1）" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%" :disabled="!!form.id">
            <el-option v-for="s in apiKeyStatuses" :key="s" :label="statusText(s)" :value="s" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="secretVisible" title="Secret 信息" width="540px">
      <el-alert type="warning" :closable="false" show-icon title="Secret 仅显示一次，请立即妥善保存，关闭后将无法再次查看" />
      <div class="secret-box">
        <div class="secret-item">
          <span class="secret-label">AccessKey</span>
          <el-input v-model="secretInfo.accessKey" readonly>
            <template #append>
              <el-button @click="copy(secretInfo.accessKey)">复制</el-button>
            </template>
          </el-input>
        </div>
        <div class="secret-item">
          <span class="secret-label">Secret</span>
          <el-input v-model="secretInfo.secret" readonly>
            <template #append>
              <el-button @click="copy(secretInfo.secret)">复制</el-button>
            </template>
          </el-input>
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="secretVisible = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  changeApiKeyStatus,
  createApiKey,
  deleteApiKey,
  listApiKeys,
  resetApiKeySecret,
  updateApiKey,
  type ApiKey,
  type CreateApiKeyResult
} from '@/api/apikey'
import { listTeams, type Team } from '@/api/team'
import { listUsers, type User } from '@/api/user'
import { getMetaAll } from '@/api/meta'

const loading = ref(false)
const saving = ref(false)
const list = ref<ApiKey[]>([])
const total = ref(0)
const teams = ref<Team[]>([])
const users = ref<User[]>([])
const apiKeyStatuses = ref<string[]>(['ENABLED', 'DISABLED', 'EXPIRED', 'REVOKED'])
const dialogVisible = ref(false)
const secretVisible = ref(false)
const secretInfo = reactive({ accessKey: '', secret: '' })
const formRef = ref<FormInstance>()

const query = reactive({ page: 1, size: 10, teamCode: '', userCode: '', status: '', keyword: '' })

const form = reactive<ApiKey>({
  id: undefined,
  teamCode: '',
  userCode: '',
  keyName: '',
  expireAt: undefined,
  allowedModels: undefined,
  status: 'ENABLED'
})

const formUsers = computed(() =>
  form.teamCode ? users.value.filter((u) => u.teamCode === form.teamCode) : users.value
)

const rules: FormRules = {
  teamCode: [{ required: true, message: '请选择团队', trigger: 'change' }],
  userCode: [{ required: true, message: '请选择绑定用户', trigger: 'change' }],
  keyName: [{ required: true, message: '请输入 Key 名称', trigger: 'blur' }]
}

function statusText(status?: string) {
  return { ENABLED: '启用', DISABLED: '停用', EXPIRED: '过期', REVOKED: '已吊销' }[status || ''] || status || '-'
}

function statusTagType(status?: string) {
  if (status === 'ENABLED') return 'success'
  if (status === 'REVOKED' || status === 'EXPIRED') return 'danger'
  return 'info'
}

async function loadTeams() {
  const res = await listTeams({ page: 1, size: 100 })
  teams.value = res.records
}

async function loadUsers(team?: string) {
  const res = await listUsers({ page: 1, size: 100, teamCode: team || '' })
  users.value = res.records
}

async function onTeamChange() {
  query.userCode = ''
  await loadUsers(query.teamCode)
  loadList()
}

async function onFormTeamChange() {
  form.userCode = ''
  await loadUsers(form.teamCode)
}

async function loadList() {
  loading.value = true
  try {
    const res = await listApiKeys(query)
    list.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function openDialog(row?: ApiKey) {
  Object.assign(form, {
    id: undefined,
    teamCode: query.teamCode || '',
    userCode: '',
    keyName: '',
    expireAt: undefined,
    allowedModels: undefined,
    status: 'ENABLED'
  })
  if (row) Object.assign(form, row)
  dialogVisible.value = true
}

async function handleSave() {
  await formRef.value?.validate()
  saving.value = true
  try {
    if (form.id) {
      await updateApiKey(form.id, form)
      ElMessage.success('更新成功')
    } else {
      const res: CreateApiKeyResult = await createApiKey(form)
      showSecret(res.apiKey.accessKey || '', res.secret)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    loadList()
  } finally {
    saving.value = false
  }
}

async function handleResetSecret(row: ApiKey) {
  await ElMessageBox.confirm(`确认重置 API Key ${row.accessKey} 的密钥？旧 Secret 将立即失效。`, '提示', { type: 'warning' })
  const res = await resetApiKeySecret(row.id!)
  showSecret(res.accessKey || row.accessKey || '', res.secret)
}

async function handleToggleStatus(row: ApiKey) {
  const target = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  await changeApiKeyStatus(row.id!, target)
  ElMessage.success(target === 'ENABLED' ? '已启用' : '已停用')
  loadList()
}

async function handleDelete(row: ApiKey) {
  await ElMessageBox.confirm(`确认删除 API Key ${row.accessKey}？删除后不可恢复。`, '提示', { type: 'warning' })
  await deleteApiKey(row.id!)
  ElMessage.success('删除成功')
  loadList()
}

function showSecret(accessKey: string, secret: string) {
  secretInfo.accessKey = accessKey
  secretInfo.secret = secret
  secretVisible.value = true
}

function copy(text: string) {
  navigator.clipboard?.writeText(text).then(() => ElMessage.success('已复制'))
}

onMounted(async () => {
  try {
    const meta = await getMetaAll()
    if (meta.apiKeyStatuses?.length) apiKeyStatuses.value = meta.apiKeyStatuses
  } catch {
    // 忽略
  }
  loadTeams()
  loadUsers()
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

.secret-box {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;

  .secret-item {
    display: flex;
    align-items: center;
    gap: 10px;

    .secret-label {
      width: 80px;
      color: #606266;
      font-weight: 500;
      flex-shrink: 0;
    }
  }
}
</style>
