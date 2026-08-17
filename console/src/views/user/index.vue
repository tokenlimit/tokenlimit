<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="header">
          <div class="header-left">
            <span class="title">User 用户</span>
            <span class="desc">用户绑定团队，可配置登录账号与角色</span>
          </div>
          <el-button type="primary" @click="openDialog()">新建用户</el-button>
        </div>
      </template>

      <div class="toolbar">
        <el-select v-model="query.teamCode" placeholder="团队" clearable filterable style="width: 150px" @change="loadList">
          <el-option v-for="t in teams" :key="t.teamCode" :label="t.teamCode" :value="t.teamCode" />
        </el-select>
        <el-select v-model="query.role" placeholder="角色" clearable style="width: 160px" @change="loadList">
          <el-option v-for="r in roles" :key="r" :label="roleText(r)" :value="r" />
        </el-select>
        <el-select v-model="query.userType" placeholder="类型" clearable style="width: 130px" @change="loadList">
          <el-option v-for="t in userTypes" :key="t" :label="t" :value="t" />
        </el-select>
        <el-select v-model="query.quotaMode" placeholder="额度模式" clearable style="width: 200px" @change="loadList">
          <el-option v-for="m in quotaModes" :key="m" :label="quotaModeText(m)" :value="m" />
        </el-select>
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 110px" @change="loadList">
          <el-option label="启用" value="ENABLED" />
          <el-option label="停用" value="DISABLED" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="搜索编码/名称/登录账号" clearable style="width: 220px" @keyup.enter="loadList" @clear="loadList" />
        <el-button @click="loadList">查询</el-button>
      </div>

      <el-table :data="list" v-loading="loading">
        <el-table-column prop="userCode" label="用户编码" min-width="140" />
        <el-table-column prop="userName" label="名称" min-width="110" />
        <el-table-column prop="username" label="登录账号" width="130">
          <template #default="{ row }">{{ row.username || '-' }}</template>
        </el-table-column>
        <el-table-column label="角色" width="120">
          <template #default="{ row }">
            <el-tag :type="roleTag(row.role)" size="small">{{ roleText(row.role) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="userType" label="类型" width="110">
          <template #default="{ row }">
            <el-tag :type="userTypeTag(row.userType)" size="small">{{ row.userType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="quotaMode" label="额度模式" width="200">
          <template #default="{ row }">
            <span>{{ quotaModeText(row.quotaMode) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="teamCode" label="团队" width="110" />
        <el-table-column label="登录" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.loginEnabled ? 'success' : 'info'">{{ row.loginEnabled ? '允许' : '禁止' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'">{{ row.status === 'ENABLED' ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">API Key</el-button>
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button link type="warning" @click="handleResetPassword(row)">重置密码</el-button>
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑用户' : '新建用户'" width="580px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="团队" prop="teamCode">
          <el-select v-model="form.teamCode" placeholder="选择团队" filterable style="width: 100%" :disabled="!!form.id">
            <el-option v-for="t in teams" :key="t.teamCode" :label="t.teamCode" :value="t.teamCode" />
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="用户编码" prop="userCode">
              <el-input v-model="form.userCode" :disabled="!!form.id" placeholder="如：user-zhangsan" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="名称" prop="userName">
              <el-input v-model="form.userName" placeholder="如：张三" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="角色" prop="role">
              <el-select v-model="form.role" style="width: 100%">
                <el-option v-for="r in roles" :key="r" :label="roleText(r)" :value="r" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="类型" prop="userType">
              <el-select v-model="form.userType" style="width: 100%">
                <el-option v-for="t in userTypes" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="额度模式" prop="quotaMode">
          <el-select v-model="form.quotaMode" style="width: 100%">
            <el-option v-for="m in quotaModes" :key="m" :label="quotaModeText(m)" :value="m" />
          </el-select>
        </el-form-item>
        <el-row v-if="!form.id" :gutter="12">
          <el-col :span="12">
            <el-form-item label="登录账号" prop="username">
              <el-input v-model="form.username" placeholder="全局唯一，用于控制台登录" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="初始密码" prop="password">
              <el-input v-model="form.password" type="password" placeholder="留空则不可登录" show-password />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="状态">
          <el-switch v-model="form.status" active-value="ENABLED" inactive-value="DISABLED" active-text="启用" inactive-text="停用" />
          <span class="login-switch">
            <el-switch v-model="form.loginEnabled" active-value="1" inactive-value="0" active-text="允许登录" inactive-text="禁止登录" />
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 用户详情：内嵌该用户的 API Key 列表 -->
    <el-drawer v-model="detailVisible" :title="`API Key 列表 - ${detailUser?.userName || ''}`" size="640px">
      <div v-loading="detailLoading">
        <div class="detail-info">
          <span>{{ detailUser?.userCode }}</span>
          <el-tag size="small">{{ detailUser?.role }}</el-tag>
          <el-tag size="small" type="info">{{ detailUser?.teamCode }}</el-tag>
        </div>
        <el-table :data="detailApiKeys" size="small">
          <el-table-column prop="accessKey" label="AccessKey" min-width="160" />
          <el-table-column prop="keyName" label="Key 名称" min-width="90">
            <template #default="{ row }">{{ row.keyName || '-' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="expireAt" label="过期" width="160">
            <template #default="{ row }">
              <span>{{ row.expireAt || '永不过期' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button link type="warning" size="small" @click="handleResetSecret(row)">重置密钥</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="detail-empty" v-if="!detailLoading && detailApiKeys.length === 0">该用户暂无 API Key，可前往「API Key 管理」创建</div>
      </div>
    </el-drawer>

    <el-dialog v-model="secretVisible" title="重置成功" width="520px">
      <el-alert type="warning" :closable="false" show-icon title="Secret 仅显示一次，请立即妥善保存" />
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

    <!-- 重置密码结果 -->
    <el-dialog v-model="pwdVisible" title="重置密码成功" width="440px">
      <el-alert type="warning" :closable="false" show-icon title="临时密码仅显示一次，用户下次登录需强制修改密码" />
      <div class="pwd-box">
        <div class="pwd-item">
          <span class="pwd-label">登录账号</span>
          <el-input :model-value="pwdResult.username" readonly />
        </div>
        <div class="pwd-item">
          <span class="pwd-label">临时密码</span>
          <el-input :model-value="pwdResult.password" readonly>
            <template #append>
              <el-button @click="copy(pwdResult.password)">复制</el-button>
            </template>
          </el-input>
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="pwdVisible = false">我知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { changeUserStatus, createUser, deleteUser, listUsers, resetUserPassword, updateUser, type User } from '@/api/user'
import { listTeams, type Team } from '@/api/team'
import { listApiKeys, resetApiKeySecret, type ApiKey } from '@/api/apikey'
import { getMetaAll } from '@/api/meta'

const loading = ref(false)
const list = ref<User[]>([])
const total = ref(0)
const teams = ref<Team[]>([])
const roles = ref<string[]>(['USER', 'TEAM_ADMIN', 'ADMIN'])
const userTypes = ref<string[]>(['EMPLOYEE', 'END_CUSTOMER', 'BOT', 'SERVICE', 'SYSTEM'])
const quotaModes = ref<string[]>(['PERSONAL_ONLY', 'TEAM_ONLY', 'PERSONAL_FIRST_THEN_TEAM'])
const dialogVisible = ref(false)
const formRef = ref<FormInstance>()

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailUser = ref<User>()
const detailApiKeys = ref<ApiKey[]>([])
const secretVisible = ref(false)
const secretInfo = reactive({ accessKey: '', secret: '' })
const pwdVisible = ref(false)
const pwdResult = reactive({ username: '', password: '' })

const query = reactive({ page: 1, size: 10, teamCode: '', role: '', userType: '', quotaMode: '', status: '', keyword: '' })

const form = reactive<User & { password?: string }>({
  id: undefined,
  teamCode: '',
  userCode: '',
  userName: '',
  userType: 'EMPLOYEE',
  quotaMode: 'PERSONAL_FIRST_THEN_TEAM',
  role: 'USER',
  username: '',
  password: '',
  loginEnabled: true,
  status: 'ENABLED'
})

const rules: FormRules = {
  teamCode: [{ required: true, message: '请选择团队', trigger: 'change' }],
  userCode: [{ required: true, message: '请输入用户编码', trigger: 'blur' }],
  userName: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  userType: [{ required: true, message: '请选择类型', trigger: 'change' }],
  quotaMode: [{ required: true, message: '请选择额度模式', trigger: 'change' }]
}

function roleText(role?: string) {
  return {
    ADMIN: '全局管理员',
    TEAM_ADMIN: 'Team 管理员',
    USER: '普通用户'
  }[role || ''] || role || '-'
}

function roleTag(role?: string) {
  return { ADMIN: 'danger', TEAM_ADMIN: 'primary', USER: 'info' }[role || ''] || 'info'
}

function quotaModeText(mode?: string) {
  return {
    PERSONAL_ONLY: '仅个人额度',
    TEAM_ONLY: '仅团队额度',
    PERSONAL_FIRST_THEN_TEAM: '个人优先，超出后团队'
  }[mode || ''] || mode || '-'
}

function userTypeTag(type?: string) {
  return { EMPLOYEE: 'info', END_CUSTOMER: 'success', BOT: 'warning', SERVICE: 'primary', SYSTEM: 'danger' }[type || ''] || 'info'
}

async function loadTeams() {
  const res = await listTeams({ page: 1, size: 100 })
  teams.value = res.records
}

async function loadList() {
  loading.value = true
  try {
    const res = await listUsers(query)
    list.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function openDialog(row?: User) {
  Object.assign(form, {
    id: undefined,
    teamCode: '',
    userCode: '',
    userName: '',
    userType: 'EMPLOYEE',
    quotaMode: 'PERSONAL_FIRST_THEN_TEAM',
    role: 'USER',
    username: '',
    password: '',
    loginEnabled: true,
    status: 'ENABLED'
  })
  if (row) {
    Object.assign(form, row, { password: undefined, loginEnabled: row.loginEnabled === undefined ? true : row.loginEnabled })
  }
  dialogVisible.value = true
}

async function handleSave() {
  await formRef.value?.validate()
  const payload: Record<string, unknown> = { ...form }
  delete payload.password
  if (form.id) {
    const { password, ...rest } = payload
    await updateUser(form.id, rest)
    ElMessage.success('更新成功')
  } else {
    if (!form.username || !form.password) {
      ElMessage.warning('请填写登录账号与初始密码')
      return
    }
    await createUser(payload as never)
    ElMessage.success('创建成功')
  }
  dialogVisible.value = false
  loadList()
}

async function handleToggleStatus(row: User) {
  const target = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  await changeUserStatus(row.id!, target)
  ElMessage.success(target === 'ENABLED' ? '已启用' : '已停用')
  loadList()
}

async function handleDelete(row: User) {
  await ElMessageBox.confirm(`确认删除用户 ${row.userName}？删除后不可恢复。`, '提示', { type: 'warning' })
  await deleteUser(row.id!)
  ElMessage.success('删除成功')
  loadList()
}

async function handleResetPassword(row: User) {
  await ElMessageBox.confirm(`确认重置用户 ${row.userName} 的登录密码？`, '提示', { type: 'warning' })
  const res = await resetUserPassword(row.id!)
  pwdResult.username = res.username
  pwdResult.password = res.password
  pwdVisible.value = true
}

async function openDetail(row: User) {
  detailUser.value = row
  detailVisible.value = true
  detailLoading.value = true
  try {
    const res = await listApiKeys({ page: 1, size: 100, teamCode: row.teamCode, userCode: row.userCode })
    detailApiKeys.value = res.records
  } finally {
    detailLoading.value = false
  }
}

async function handleResetSecret(row: ApiKey) {
  await ElMessageBox.confirm(`确认重置 API Key ${row.accessKey} 的密钥？旧 Secret 将失效。`, '提示', { type: 'warning' })
  const res = await resetApiKeySecret(row.id!)
  secretInfo.accessKey = res.accessKey || row.accessKey || ''
  secretInfo.secret = res.secret
  secretVisible.value = true
}

function copy(text: string) {
  navigator.clipboard?.writeText(text).then(() => ElMessage.success('已复制'))
}

onMounted(async () => {
  try {
    const meta = await getMetaAll()
    if (meta.roles?.length) roles.value = meta.roles
    if (meta.userTypes?.length) userTypes.value = meta.userTypes
    if (meta.quotaModes?.length) quotaModes.value = meta.quotaModes
  } catch {
    // 忽略
  }
  loadTeams()
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

.login-switch {
  margin-left: 16px;
}

.detail-info {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  color: #606266;
}

.detail-empty {
  margin-top: 20px;
  text-align: center;
  color: #909399;
}

.secret-box,
.pwd-box {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;

  .secret-item,
  .pwd-item {
    display: flex;
    align-items: center;
    gap: 10px;

    .secret-label,
    .pwd-label {
      width: 80px;
      color: #606266;
      font-weight: 500;
      flex-shrink: 0;
    }
  }
}
</style>
