<template>
  <div class="page">
    <el-tabs v-model="activeTab">
      <!-- ==================== Tab 1: Provider 凭证 ==================== -->
      <el-tab-pane label="Provider 凭证" name="credential">
        <el-card>
          <template #header>
            <div class="header">
              <div class="header-left">
                <span class="title">Provider 凭证</span>
                <span class="desc">托管供应商密钥（AES 加密存储，永不回显），供模型策略绑定</span>
              </div>
              <el-button type="primary" @click="openCredDialog()">新增凭证</el-button>
            </div>
          </template>

          <div class="toolbar">
            <el-select v-model="credQuery.provider" placeholder="供应商" clearable style="width: 150px" @change="loadCreds">
              <el-option v-for="p in providerOptions" :key="p.provider" :label="p.providerName" :value="p.provider" />
            </el-select>
            <el-select v-model="credQuery.scopeType" placeholder="作用域" clearable style="width: 130px" @change="loadCreds">
              <el-option label="GLOBAL（全局）" value="GLOBAL" />
              <el-option label="TEAM（团队专属）" value="TEAM" />
            </el-select>
            <el-input v-model="credQuery.keyword" placeholder="搜索凭证编码 / 名称" clearable style="width: 220px" @keyup.enter="loadCreds" @clear="loadCreds" />
            <el-button @click="loadCreds">查询</el-button>
          </div>

          <el-table :data="credList" v-loading="credLoading">
            <el-table-column prop="credentialCode" label="凭证编码" min-width="140" />
            <el-table-column prop="credentialName" label="名称" min-width="130" />
            <el-table-column prop="provider" label="供应商" width="120">
              <template #default="{ row }">
                <el-tag size="small">{{ row.provider }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="作用域" width="150">
              <template #default="{ row }">
                <el-tag :type="row.scopeType === 'GLOBAL' ? 'primary' : 'warning'" size="small">
                  {{ row.scopeType === 'GLOBAL' ? 'GLOBAL（全局）' : `TEAM（${row.teamCode || '-'}）` }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="apiBaseUrl" label="API Base URL" min-width="220" show-overflow-tooltip />
            <el-table-column prop="model" label="默认模型" width="130">
              <template #default="{ row }">{{ row.model || '-' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openCredDialog(row)">编辑</el-button>
                <el-button link :type="row.status === 'ACTIVE' ? 'warning' : 'success'" @click="toggleCred(row)">
                  {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
                </el-button>
                <el-button link type="danger" @click="deleteCred(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            class="pagination"
            v-model:current-page="credQuery.page"
            v-model:page-size="credQuery.size"
            :total="credTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadCreds"
            @size-change="loadCreds"
          />
        </el-card>
      </el-tab-pane>

      <!-- ==================== Tab 2: 模型策略 ==================== -->
      <el-tab-pane label="模型策略" name="policy">
        <el-card>
          <template #header>
            <div class="header">
              <div class="header-left">
                <span class="title">Team Model Policy</span>
                <span class="desc">映射「团队 + 模型 → Provider 凭证」，查找优先级：团队专属 → 全局</span>
              </div>
              <el-button type="primary" @click="openPolicyDialog()">新增策略</el-button>
            </div>
          </template>

          <div class="toolbar">
            <el-select v-model="policyQuery.teamCode" placeholder="团队" clearable filterable style="width: 160px" @change="loadPolicies">
              <el-option v-for="t in teams" :key="t.teamCode" :label="t.teamCode" :value="t.teamCode" />
            </el-select>
            <el-input v-model="policyQuery.model" placeholder="模型名（如 gpt-4o）" clearable style="width: 200px" @keyup.enter="loadPolicies" @clear="loadPolicies" />
            <el-button @click="loadPolicies">查询</el-button>
          </div>

          <el-table :data="policyList" v-loading="policyLoading">
            <el-table-column prop="teamCode" label="团队" min-width="120" />
            <el-table-column prop="model" label="模型" min-width="140">
              <template #default="{ row }"><el-tag size="small">{{ row.model || '*' }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="credentialCode" label="凭证" min-width="140" />
            <el-table-column label="启用" width="90">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="160">
              <template #default="{ row }">{{ row.remark || '-' }}</template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="170">
              <template #default="{ row }">{{ row.createdAt || '-' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openPolicyDialog(row)">编辑</el-button>
                <el-button link type="danger" @click="deletePolicy(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            class="pagination"
            v-model:current-page="policyQuery.page"
            v-model:page-size="policyQuery.size"
            :total="policyTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadPolicies"
            @size-change="loadPolicies"
          />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- ==================== 凭证弹窗 ==================== -->
    <el-dialog v-model="credDialogVisible" :title="credForm.id ? '编辑凭证' : '新增凭证'" width="560px">
      <el-form ref="credFormRef" :model="credForm" :rules="credRules" label-width="120px">
        <el-form-item label="凭证编码" prop="credentialCode">
          <el-input v-model="credForm.credentialCode" :disabled="!!credForm.id" placeholder="如：cred-openai-global" />
        </el-form-item>
        <el-form-item label="凭证名称" prop="credentialName">
          <el-input v-model="credForm.credentialName" placeholder="如：OpenAI 生产密钥" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="供应商" prop="provider">
              <el-select v-model="credForm.provider" allow-create filterable style="width: 100%" @change="onProviderChange">
                <el-option v-for="t in providerTemplates" :key="t.provider" :label="t.providerName" :value="t.provider">
                  <span>{{ t.providerName }}</span>
                  <span class="option-code">{{ t.provider }}</span>
                </el-option>
              </el-select>
              <div v-if="providerTemplateTip" class="form-tip" style="margin-top: 4px">{{ providerTemplateTip }}</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="作用域" prop="scopeType">
              <el-select v-model="credForm.scopeType" style="width: 100%">
                <el-option label="GLOBAL（全局）" value="GLOBAL" />
                <el-option label="TEAM（团队专属）" value="TEAM" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item v-if="credForm.scopeType === 'TEAM'" label="绑定团队" prop="teamCode">
          <el-select v-model="credForm.teamCode" placeholder="选择团队" filterable style="width: 100%">
            <el-option v-for="t in teams" :key="t.teamCode" :label="t.teamCode" :value="t.teamCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="API Base URL" prop="apiBaseUrl">
          <el-input v-model="credForm.apiBaseUrl" placeholder="如：https://api.openai.com" />
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input v-model="credForm.apiKey" type="password" show-password :placeholder="credForm.id ? '留空则不修改（密钥不回显）' : '输入供应商 API Key'" />
          <div class="form-tip">密钥经 AES 加密存储，后台永不回显</div>
        </el-form-item>
        <el-form-item label="默认模型">
          <el-input v-model="credForm.model" placeholder="如：gpt-4o-mini" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="credForm.remark" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="credForm.status" active-value="ACTIVE" inactive-value="INACTIVE" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="credDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="credSaving" @click="saveCred">保存</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 模型策略弹窗 ==================== -->
    <el-dialog v-model="policyDialogVisible" :title="policyForm.id ? '编辑策略' : '新增策略'" width="520px">
      <el-form ref="policyFormRef" :model="policyForm" :rules="policyRules" label-width="100px">
        <el-form-item label="团队" prop="teamCode">
          <el-select v-model="policyForm.teamCode" placeholder="选择团队" filterable style="width: 100%" :disabled="!!policyForm.id" @change="loadPolicyCreds">
            <el-option v-for="t in teams" :key="t.teamCode" :label="t.teamCode" :value="t.teamCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型" prop="model">
          <el-input v-model="policyForm.model" :disabled="!!policyForm.id" placeholder="如：gpt-4o（留空表示通配）" />
        </el-form-item>
        <el-form-item label="凭证" prop="credentialCode">
          <el-select v-model="policyForm.credentialCode" placeholder="选择凭证（团队专属优先，GLOBAL 兜底）" filterable style="width: 100%">
            <el-option v-for="c in policyCreds" :key="c.credentialCode" :label="`${c.credentialCode}（${c.provider}${c.scopeType === 'TEAM' ? '·' + c.teamCode : '·GLOBAL'}）`" :value="c.credentialCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="policyForm.enabled" active-value="1" inactive-value="0" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="policyForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="policyDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="policySaving" @click="savePolicy">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createModelPolicy,
  createProviderCredential,
  deleteModelPolicy,
  deleteProviderCredential,
  listModelPolicies,
  listPolicyCredentials,
  listProviderCredentials,
  listProviderOptions,
  listProviderTemplates,
  toggleProviderCredential,
  updateModelPolicy,
  updateProviderCredential,
  type ProviderCredential,
  type ProviderOption,
  type ProviderTemplate,
  type TeamModelPolicy
} from '@/api/provider'
import { listTeams, type Team } from '@/api/team'

const activeTab = ref('credential')

// ============ Provider 凭证 ============
const credLoading = ref(false)
const credSaving = ref(false)
const credList = ref<ProviderCredential[]>([])
const credTotal = ref(0)
const providerOptions = ref<ProviderOption[]>([])
const providerTemplates = ref<ProviderTemplate[]>([])
const providerTemplateTip = ref('')
const teams = ref<Team[]>([])
const credDialogVisible = ref(false)
const credFormRef = ref<FormInstance>()

const credQuery = reactive({ page: 1, size: 10, provider: '', scopeType: '', keyword: '' })

const credForm = reactive<ProviderCredential & { apiKey?: string }>({
  id: undefined,
  credentialCode: '',
  credentialName: '',
  provider: '',
  scopeType: 'GLOBAL',
  teamCode: '',
  apiBaseUrl: '',
  apiKey: '',
  model: '',
  status: 'ACTIVE',
  remark: ''
})

const credRules: FormRules = {
  credentialCode: [{ required: true, message: '请输入凭证编码', trigger: 'blur' }],
  credentialName: [{ required: true, message: '请输入凭证名称', trigger: 'blur' }],
  provider: [{ required: true, message: '请选择/输入供应商', trigger: 'change' }],
  scopeType: [{ required: true, message: '请选择作用域', trigger: 'change' }],
  apiBaseUrl: [{ required: true, message: '请输入 API Base URL', trigger: 'blur' }]
}

async function loadCreds() {
  credLoading.value = true
  try {
    const res = await listProviderCredentials(credQuery)
    credList.value = res.records
    credTotal.value = res.total
  } finally {
    credLoading.value = false
  }
}

function openCredDialog(row?: ProviderCredential) {
  Object.assign(credForm, {
    id: undefined,
    credentialCode: '',
    credentialName: '',
    provider: '',
    scopeType: 'GLOBAL',
    teamCode: '',
    apiBaseUrl: '',
    apiKey: '',
    model: '',
    status: 'ACTIVE',
    remark: ''
  })
  if (row) Object.assign(credForm, row, { apiKey: '' })
  providerTemplateTip.value = ''
  credDialogVisible.value = true
}

/** 选择内置供应商模板后，自动填充 Base URL 并提示注意事项 */
function onProviderChange(val: string) {
  const tpl = providerTemplates.value.find((t) => t.provider === val)
  if (!tpl) {
    providerTemplateTip.value = ''
    return
  }
  if (tpl.baseUrl) {
    credForm.apiBaseUrl = tpl.baseUrl
  }
  if (tpl.requiresEndpoint) {
    providerTemplateTip.value = `${tpl.providerName} 需要在 Base URL 末尾拼接控制台创建的 Endpoint ID（如 /api/v3/ep-xxx）`
  } else if (!tpl.openAiCompatible) {
    providerTemplateTip.value = `${tpl.providerName} 原生 API 不兼容 OpenAI 协议，需协议转换 Adapter，MVP 阶段暂不支持直接透传`
  } else {
    providerTemplateTip.value = ''
  }
}

async function saveCred() {
  await credFormRef.value?.validate()
  const payload = {
    credentialCode: credForm.credentialCode || '',
    credentialName: credForm.credentialName || '',
    provider: credForm.provider || '',
    scopeType: credForm.scopeType as 'GLOBAL' | 'TEAM',
    teamCode: credForm.scopeType === 'TEAM' ? credForm.teamCode : undefined,
    apiBaseUrl: credForm.apiBaseUrl,
    apiKey: credForm.apiKey || undefined,
    model: credForm.model,
    status: credForm.status,
    remark: credForm.remark
  }
  credSaving.value = true
  try {
    if (credForm.id) {
      await updateProviderCredential(credForm.credentialCode!, payload)
      ElMessage.success('更新成功')
    } else {
      await createProviderCredential(payload)
      ElMessage.success('创建成功')
    }
    credDialogVisible.value = false
    loadCreds()
  } finally {
    credSaving.value = false
  }
}

async function toggleCred(row: ProviderCredential) {
  await toggleProviderCredential(row.credentialCode!)
  ElMessage.success(row.status === 'ACTIVE' ? '已停用' : '已启用')
  loadCreds()
}

async function deleteCred(row: ProviderCredential) {
  await ElMessageBox.confirm(`确认删除凭证 ${row.credentialCode}？删除后模型策略将无法使用该凭证。`, '提示', { type: 'warning' })
  await deleteProviderCredential(row.credentialCode!)
  ElMessage.success('删除成功')
  loadCreds()
}

// ============ 模型策略 ============
const policyLoading = ref(false)
const policySaving = ref(false)
const policyList = ref<TeamModelPolicy[]>([])
const policyTotal = ref(0)
const policyCreds = ref<ProviderCredential[]>([])
const policyDialogVisible = ref(false)
const policyFormRef = ref<FormInstance>()

const policyQuery = reactive({ page: 1, size: 10, teamCode: '', model: '' })

const policyForm = reactive<TeamModelPolicy & { enabled: number | boolean }>({
  id: undefined,
  teamCode: '',
  model: '',
  credentialCode: '',
  enabled: true,
  remark: ''
})

const policyRules: FormRules = {
  teamCode: [{ required: true, message: '请选择团队', trigger: 'change' }],
  credentialCode: [{ required: true, message: '请选择凭证', trigger: 'change' }]
}

async function loadPolicies() {
  policyLoading.value = true
  try {
    const res = await listModelPolicies(policyQuery)
    policyList.value = res.records
    policyTotal.value = res.total
  } finally {
    policyLoading.value = false
  }
}

async function loadPolicyCreds() {
  policyCreds.value = await listPolicyCredentials(policyForm.teamCode)
}

function openPolicyDialog(row?: TeamModelPolicy) {
  Object.assign(policyForm, { id: undefined, teamCode: '', model: '', credentialCode: '', enabled: true, remark: '' })
  if (row) Object.assign(policyForm, row, { enabled: row.enabled === undefined ? true : row.enabled })
  policyDialogVisible.value = true
  if (policyForm.teamCode) loadPolicyCreds()
  else policyCreds.value = []
}

async function savePolicy() {
  await policyFormRef.value?.validate()
  policySaving.value = true
  try {
    if (policyForm.id) {
      await updateModelPolicy(policyForm.id, {
        credentialCode: policyForm.credentialCode,
        enabled: !!policyForm.enabled,
        remark: policyForm.remark
      })
      ElMessage.success('更新成功')
    } else {
      await createModelPolicy({
        teamCode: policyForm.teamCode,
        model: policyForm.model,
        credentialCode: policyForm.credentialCode,
        enabled: !!policyForm.enabled,
        remark: policyForm.remark
      })
      ElMessage.success('创建成功')
    }
    policyDialogVisible.value = false
    loadPolicies()
  } finally {
    policySaving.value = false
  }
}

async function deletePolicy(row: TeamModelPolicy) {
  await ElMessageBox.confirm(`确认删除团队 ${row.teamCode} 的模型 ${row.model || '*'} 策略？`, '提示', { type: 'warning' })
  await deleteModelPolicy(row.id!)
  ElMessage.success('删除成功')
  loadPolicies()
}

async function loadTeams() {
  const res = await listTeams({ page: 1, size: 100 })
  teams.value = res.records
}

onMounted(async () => {
  providerOptions.value = await listProviderOptions()
  providerTemplates.value = await listProviderTemplates()
  loadTeams()
  loadCreds()
  loadPolicies()
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

.form-tip {
  width: 100%;
  color: #909399;
  font-size: 12px;
}

.option-code {
  float: right;
  color: #909399;
  font-size: 12px;
}
</style>
