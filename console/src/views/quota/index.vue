<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="header">
          <div class="header-left">
            <span class="title">Quota 配额规则</span>
            <span class="desc">按 Team / User 设置 Token、费用、请求数限制</span>
          </div>
          <el-button type="primary" @click="openDialog()">新建规则</el-button>
        </div>
      </template>

      <div class="toolbar">
        <el-select v-model="query.targetType" placeholder="对象类型" clearable style="width: 140px" @change="loadList">
          <el-option v-for="t in targetTypes" :key="t" :label="targetText(t)" :value="t" />
        </el-select>
        <el-select v-model="query.limitType" placeholder="限制类型" clearable style="width: 140px" @change="loadList">
          <el-option v-for="t in limitTypes" :key="t" :label="limitText(t)" :value="t" />
        </el-select>
        <el-select v-model="query.period" placeholder="周期" clearable style="width: 120px" @change="loadList">
          <el-option v-for="p in periods" :key="p" :label="periodText(p)" :value="p" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="搜索规则/对象编码" clearable style="width: 200px" @keyup.enter="loadList" @clear="loadList" />
        <el-button @click="loadList">查询</el-button>
      </div>

      <el-table :data="list" v-loading="loading">
        <el-table-column prop="ruleCode" label="规则编码" min-width="180" />
        <el-table-column label="对象类型" width="110">
          <template #default="{ row }">
            <el-tag size="small">{{ targetText(row.targetType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="targetCode" label="对象" min-width="140" />
        <el-table-column prop="model" label="模型" width="120">
          <template #default="{ row }">{{ row.model || '全部' }}</template>
        </el-table-column>
        <el-table-column label="限制类型" width="110">
          <template #default="{ row }">{{ limitText(row.limitType) }}</template>
        </el-table-column>
        <el-table-column label="限额" width="130">
          <template #default="{ row }">{{ formatLimit(row) }}</template>
        </el-table-column>
        <el-table-column label="周期" width="100">
          <template #default="{ row }">{{ periodText(row.period) }}</template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="90" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button link :type="row.enabled ? 'warning' : 'success'" @click="handleToggle(row)">
              {{ row.enabled ? '停用' : '启用' }}
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑规则' : '新建规则'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="规则编码" prop="ruleCode">
          <el-input v-model="form.ruleCode" :disabled="!!form.id" placeholder="如：rule-team-day-token" />
        </el-form-item>
        <el-form-item label="对象类型" prop="targetType">
          <el-select v-model="form.targetType" style="width: 100%" @change="onTargetTypeChange">
            <el-option v-for="t in targetTypes" :key="t" :label="targetText(t)" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="对象编码" prop="targetCode">
          <el-select v-model="form.targetCode" filterable allow-create default-first-option placeholder="选择或输入对象编码" style="width: 100%">
            <el-option v-for="c in targetOptions" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型">
          <el-input v-model="form.model" placeholder="留空表示全部模型，如 gpt-4o" />
        </el-form-item>
        <el-form-item label="限制类型" prop="limitType">
          <el-select v-model="form.limitType" style="width: 100%">
            <el-option v-for="t in limitTypes" :key="t" :label="limitText(t)" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="限额" prop="limitValue">
          <el-input-number v-model="form.limitValue" :min="0" :precision="limitTypeIsCost ? 4 : 0" style="width: 100%" />
        </el-form-item>
        <el-form-item label="周期" prop="period">
          <el-select v-model="form.period" style="width: 100%">
            <el-option v-for="p in periods" :key="p" :label="periodText(p)" :value="p" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="0" style="width: 100%" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled" active-text="启用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { changeQuotaRuleStatus, createQuotaRule, deleteQuotaRule, listQuotaRules, updateQuotaRule, type QuotaRule } from '@/api/quotaRule'
import { getMetaAll, type MetaAll } from '@/api/meta'

const loading = ref(false)
const list = ref<QuotaRule[]>([])
const total = ref(0)
const meta = ref<MetaAll>()
const dialogVisible = ref(false)
const formRef = ref<FormInstance>()

const query = reactive({ page: 1, size: 10, targetType: '', limitType: '', period: '', keyword: '' })

const form = reactive<QuotaRule>({
  id: undefined,
  ruleCode: '',
  targetType: 'TEAM',
  targetCode: '',
  model: '',
  limitType: 'TOKEN',
  limitValue: 100000,
  period: 'DAY',
  priority: 10,
  enabled: true,
  description: ''
})

const rules: FormRules = {
  ruleCode: [{ required: true, message: '请输入规则编码', trigger: 'blur' }],
  targetType: [{ required: true, message: '请选择对象类型', trigger: 'change' }],
  targetCode: [{ required: true, message: '请输入对象编码', trigger: 'change' }],
  limitType: [{ required: true, message: '请选择限制类型', trigger: 'change' }],
  limitValue: [{ required: true, message: '请输入限额', trigger: 'change' }],
  period: [{ required: true, message: '请选择周期', trigger: 'change' }]
}

const targetTypes = computed(() => meta.value?.targetTypes || ['TEAM', 'USER'])
const limitTypes = computed(() => meta.value?.limitTypes || ['TOKEN', 'COST', 'REQUEST_COUNT', 'RPM', 'TPM'])
const periods = computed(() => meta.value?.periods || ['MINUTE', 'HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR', 'TOTAL'])

const limitTypeIsCost = computed(() => form.limitType === 'COST')

const targetOptions = computed(() => {
  const type = form.targetType
  if (!type) return []
  if (type === 'TEAM') return meta.value?.teams.map((t) => t.teamCode) || []
  if (type === 'USER') return meta.value?.users.map((u) => u.userCode) || []
  return []
})

function targetText(t: string) {
  return { TEAM: '团队', USER: '用户' }[t] || t
}
function limitText(t: string) {
  return { TOKEN: 'Token', COST: '费用', REQUEST_COUNT: '请求数', RPM: 'RPM', TPM: 'TPM' }[t] || t
}
function periodText(p: string) {
  return { MINUTE: '分钟', HOUR: '小时', DAY: '天', WEEK: '周', MONTH: '月', YEAR: '年', TOTAL: '总计' }[p] || p
}
function formatLimit(row: QuotaRule) {
  if (row.limitType === 'COST') return `¥${Number(row.limitValue).toLocaleString()}`
  return Number(row.limitValue).toLocaleString()
}

function onTargetTypeChange() {
  form.targetCode = ''
}

async function loadMeta() {
  try {
    meta.value = await getMetaAll()
  } catch {
    meta.value = undefined
  }
}

async function loadList() {
  loading.value = true
  try {
    const res = await listQuotaRules(query)
    list.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function openDialog(row?: QuotaRule) {
  Object.assign(form, {
    id: undefined, ruleCode: '', targetType: 'TEAM',
    targetCode: '', model: '', limitType: 'TOKEN', limitValue: 100000, period: 'DAY',
    priority: 10, enabled: true, description: ''
  })
  if (row) {
    Object.assign(form, row, { limitValue: Number(row.limitValue) })
  }
  dialogVisible.value = true
}

async function handleSave() {
  await formRef.value?.validate()
  const payload = { ...form, model: form.model || undefined }
  if (form.id) {
    await updateQuotaRule(form.id, payload)
    ElMessage.success('更新成功')
  } else {
    await createQuotaRule(payload)
    ElMessage.success('创建成功')
  }
  dialogVisible.value = false
  loadList()
}

async function handleToggle(row: QuotaRule) {
  await changeQuotaRuleStatus(row.id!, !row.enabled)
  ElMessage.success(row.enabled ? '已停用' : '已启用')
  loadList()
}

async function handleDelete(row: QuotaRule) {
  await ElMessageBox.confirm(`确认删除规则 ${row.ruleCode}？`, '提示', { type: 'warning' })
  await deleteQuotaRule(row.id!)
  ElMessage.success('删除成功')
  loadList()
}

onMounted(() => {
  loadMeta()
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
