<template>
  <div class="page">
    <!-- 统计卡片 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :span="6">
        <el-card>
          <div class="stat"><div class="value">{{ stats.monthTasks }}</div><div class="label">本月对账任务</div></div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card>
          <div class="stat"><div class="value danger">{{ stats.diffItems }}</div><div class="label">发现差异（>3%）</div></div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card>
          <div class="stat"><div class="value warn">{{ stats.disputeItems }}</div><div class="label">待处理争议</div></div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card>
          <div class="stat"><div class="value primary">{{ (stats.avgDiffRate * 100).toFixed(2) }}%</div><div class="label">平均差异率</div></div>
        </el-card>
      </el-col>
    </el-row>

    <el-card>
      <el-tabs v-model="activeTab">
        <!-- 对账任务 -->
        <el-tab-pane label="对账任务" name="tasks">
          <div class="toolbar">
            <el-date-picker v-model="query.billDate" type="date" value-format="YYYY-MM-DD" placeholder="账单日期" style="width: 160px" @change="loadList" />
            <el-input v-model="query.provider" placeholder="供应商，如 openai" clearable style="width: 180px" @keyup.enter="loadList" @clear="loadList" />
            <el-select v-model="query.status" placeholder="任务状态" clearable style="width: 150px" @change="loadList">
              <el-option v-for="s in taskStatuses" :key="s" :label="taskStatusLabel(s)" :value="s" />
            </el-select>
            <el-button type="primary" @click="openCreateDialog">创建对账任务</el-button>
            <el-button @click="loadList">查询</el-button>
          </div>

          <el-table :data="taskList" v-loading="loading">
            <el-table-column prop="taskCode" label="任务编码" min-width="160" />
            <el-table-column prop="billDate" label="账单日期" width="110" />
            <el-table-column prop="provider" label="供应商" width="120">
              <template #default="{ row }"><el-tag>{{ row.provider }}</el-tag></template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)">{{ taskStatusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="totalItems" label="明细数" width="80" />
            <el-table-column prop="diffItems" label="差异数" width="80">
              <template #default="{ row }">
                <span :class="row.diffItems > 0 ? 'danger-text' : ''">{{ row.diffItems }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="disputeItems" label="争议数" width="80" />
            <el-table-column label="平均差异率" width="110">
              <template #default="{ row }">{{ ((row.avgDiffRate || 0) * 100).toFixed(2) }}%</template>
            </el-table-column>
            <el-table-column prop="executedAt" label="执行时间" width="170" />
            <el-table-column label="操作" width="230" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="row.status !== 'PENDING'" @click="handleExecute(row)">执行</el-button>
                <el-button link type="primary" :disabled="row.status !== 'COMPLETED'" @click="openItems(row)">明细</el-button>
                <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            class="pagination"
            v-model:current-page="query.page"
            v-model:page-size="query.size"
            :total="taskTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadList"
            @size-change="loadList"
          />
        </el-tab-pane>

        <!-- 供应商账单 -->
        <el-tab-pane label="供应商账单" name="bills">
          <div class="toolbar">
            <el-date-picker v-model="billQuery.billDate" type="date" value-format="YYYY-MM-DD" placeholder="账单日期" style="width: 160px" @change="loadBills" />
            <el-input v-model="billQuery.provider" placeholder="供应商" clearable style="width: 150px" @keyup.enter="loadBills" @clear="loadBills" />
            <el-input v-model="billQuery.model" placeholder="模型" clearable style="width: 150px" @keyup.enter="loadBills" @clear="loadBills" />
            <el-button type="primary" @click="openBillDialog()">新增账单</el-button>
            <el-button @click="loadBills">查询</el-button>
          </div>

          <el-table :data="billList" v-loading="billLoading">
            <el-table-column prop="billDate" label="账单日期" width="110" />
            <el-table-column prop="provider" label="供应商" width="120">
              <template #default="{ row }"><el-tag>{{ row.provider }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="model" label="模型" min-width="160" />
            <el-table-column label="供应商 Tokens" width="140">
              <template #default="{ row }">{{ (row.providerTokens || 0).toLocaleString() }}</template>
            </el-table-column>
            <el-table-column label="供应商成本（元）" width="140">
              <template #default="{ row }">{{ (row.providerCost || 0).toFixed(4) }}</template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="160" />
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openBillDialog(row)">编辑</el-button>
                <el-button link type="danger" @click="handleDeleteBill(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            class="pagination"
            v-model:current-page="billQuery.page"
            v-model:page-size="billQuery.size"
            :total="billTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadBills"
            @size-change="loadBills"
          />
        </el-tab-pane>

        <!-- 模型价格 -->
        <el-tab-pane label="模型价格" name="prices">
          <div class="toolbar">
            <el-input v-model="priceQuery.provider" placeholder="供应商" clearable style="width: 150px" @keyup.enter="loadPrices" @clear="loadPrices" />
            <el-input v-model="priceQuery.model" placeholder="模型" clearable style="width: 150px" @keyup.enter="loadPrices" @clear="loadPrices" />
            <el-button type="primary" @click="openPriceDialog()">新增价格</el-button>
            <el-button @click="loadPrices">查询</el-button>
          </div>

          <el-table :data="priceList" v-loading="priceLoading">
            <el-table-column prop="provider" label="供应商" width="120">
              <template #default="{ row }"><el-tag>{{ row.provider }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="model" label="模型" min-width="180" />
            <el-table-column label="输入单价（元/百万）" width="160">
              <template #default="{ row }">{{ (((row.inputPricePerToken || 0) * 1000000).toFixed(2)) }}</template>
            </el-table-column>
            <el-table-column label="输出单价（元/百万）" width="160">
              <template #default="{ row }">{{ (((row.outputPricePerToken || 0) * 1000000).toFixed(2)) }}</template>
            </el-table-column>
            <el-table-column prop="currency" label="币种" width="90" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'">{{ row.status === 'ENABLED' ? '启用' : '停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="180" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openPriceDialog(row)">编辑</el-button>
                <el-button link type="warning" @click="handleTogglePrice(row)">{{ row.status === 'ENABLED' ? '停用' : '启用' }}</el-button>
                <el-button link type="danger" @click="handleDeletePrice(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            class="pagination"
            v-model:current-page="priceQuery.page"
            v-model:page-size="priceQuery.size"
            :total="priceTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadPrices"
            @size-change="loadPrices"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 创建对账任务 -->
    <el-dialog v-model="createVisible" title="创建对账任务" width="460px">
      <el-form :model="createForm" label-width="90px">
        <el-form-item label="账单日期" required>
          <el-date-picker v-model="createForm.billDate" type="date" value-format="YYYY-MM-DD" placeholder="选择账单日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="供应商" required>
          <el-input v-model="createForm.provider" placeholder="如：openai / anthropic / deepseek" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="handleCreateTask">创建</el-button>
      </template>
    </el-dialog>

    <!-- 账单编辑 -->
    <el-dialog v-model="billDialogVisible" :title="billForm.id ? '编辑账单' : '新增账单'" width="500px">
      <el-form :model="billForm" label-width="110px">
        <el-form-item label="账单日期" required>
          <el-date-picker v-model="billForm.billDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="供应商" required>
          <el-input v-model="billForm.provider" placeholder="如：openai" />
        </el-form-item>
        <el-form-item label="模型" required>
          <el-input v-model="billForm.model" placeholder="如：gpt-4o" />
        </el-form-item>
        <el-form-item label="供应商 Tokens">
          <el-input-number v-model="billForm.providerTokens" :min="0" style="width: 100%" />
        </el-form-item>
        <el-form-item label="供应商成本">
          <el-input-number v-model="billForm.providerCost" :min="0" :precision="4" :step="0.01" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="billForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="billDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSaveBill">保存</el-button>
      </template>
    </el-dialog>

    <!-- 模型价格编辑 -->
    <el-dialog v-model="priceDialogVisible" :title="priceForm.id ? '编辑价格' : '新增价格'" width="500px">
      <el-form :model="priceForm" label-width="130px">
        <el-form-item label="供应商" required>
          <el-input v-model="priceForm.provider" placeholder="如：openai" :disabled="!!priceForm.id" />
        </el-form-item>
        <el-form-item label="模型" required>
          <el-input v-model="priceForm.model" placeholder="如：gpt-4o" :disabled="!!priceForm.id" />
        </el-form-item>
        <el-form-item label="输入单价（元/百万）">
          <el-input-number v-model="priceForm.inputPerMillion" :min="0" :precision="4" :step="0.1" style="width: 100%" />
          <div class="form-tip">每百万 Token 单价，保存后自动折算为每 Token 单价</div>
        </el-form-item>
        <el-form-item label="输出单价（元/百万）">
          <el-input-number v-model="priceForm.outputPerMillion" :min="0" :precision="4" :step="0.1" style="width: 100%" />
          <div class="form-tip">每百万 Token 单价，保存后自动折算为每 Token 单价</div>
        </el-form-item>
        <el-form-item label="缓存读取单价（元/百万）">
          <el-input-number v-model="priceForm.cacheReadPerMillion" :min="0" :precision="4" :step="0.1" style="width: 100%" placeholder="可选" />
          <div class="form-tip">可选：缓存命中 Token 单价（OpenAI 5 折 / DeepSeek 1 折 / Anthropic 1 折），留空按正常输入价计费</div>
        </el-form-item>
        <el-form-item label="缓存写入单价（元/百万）">
          <el-input-number v-model="priceForm.cacheWritePerMillion" :min="0" :precision="4" :step="0.1" style="width: 100%" placeholder="可选" />
          <div class="form-tip">可选：缓存写入 Token 单价（Anthropic 为正常输入价 1.25 倍），留空按正常输入价计费</div>
        </el-form-item>
        <el-form-item label="币种">
          <el-select v-model="priceForm.currency" style="width: 100%">
            <el-option label="CNY（人民币）" value="CNY" />
            <el-option label="USD（美元）" value="USD" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="priceForm.status" style="width: 100%">
            <el-option label="启用" value="ENABLED" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="priceDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSavePrice">保存</el-button>
      </template>
    </el-dialog>

    <!-- 对账明细 -->
    <el-drawer v-model="itemsVisible" :title="`对账明细 - ${currentTask?.taskCode || ''}`" size="75%">
      <div class="toolbar">
        <el-select v-model="itemQuery.status" placeholder="状态" clearable style="width: 140px" @change="loadItems">
          <el-option v-for="s in itemStatuses" :key="s" :label="itemStatusLabel(s)" :value="s" />
        </el-select>
        <el-input v-model="itemQuery.model" placeholder="模型" clearable style="width: 160px" @keyup.enter="loadItems" @clear="loadItems" />
        <el-button @click="loadItems">查询</el-button>
      </div>

      <el-table :data="itemList" v-loading="itemLoading">
        <el-table-column prop="model" label="模型" min-width="150" />
        <el-table-column label="我方 Tokens" width="120">
          <template #default="{ row }">{{ (row.ourTokens || 0).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="供应商 Tokens" width="130">
          <template #default="{ row }">{{ (row.providerTokens || 0).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="差异 Tokens" width="120">
          <template #default="{ row }">
            <span :class="(row.tokenDiff || 0) !== 0 ? 'danger-text' : ''">{{ fmtSigned(row.tokenDiff) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="差异率" width="100">
          <template #default="{ row }">
            <span :class="((row.tokenDiffRate || 0) * 100) > 3 ? 'danger-text' : ''">{{ ((row.tokenDiffRate || 0) * 100).toFixed(2) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="我方成本" width="110">
          <template #default="{ row }">¥{{ (row.ourCost || 0).toFixed(4) }}</template>
        </el-table-column>
        <el-table-column label="供应商成本" width="120">
          <template #default="{ row }">¥{{ (row.providerCost || 0).toFixed(4) }}</template>
        </el-table-column>
        <el-table-column label="成本差异率" width="110">
          <template #default="{ row }">{{ ((row.costDiffRate || 0) * 100).toFixed(2) }}%</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="itemStatusType(row.status)">{{ itemStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="120" />
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="warning" :disabled="row.status === 'DISPUTED'" @click="handleDispute(row)">发起争议</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pagination"
        v-model:current-page="itemQuery.page"
        v-model:page-size="itemQuery.size"
        :total="itemTotal"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="loadItems"
        @size-change="loadItems"
      />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeModelPriceStatus,
  changeReconcileItemStatus,
  createModelPrice,
  createReconcileTask,
  createVendorBill,
  deleteModelPrice,
  deleteReconcileTask,
  deleteVendorBill,
  executeReconcileTask,
  getReconcileStats,
  listModelPrices,
  listReconcileItems,
  listReconcileTasks,
  listVendorBills,
  updateModelPrice,
  updateVendorBill,
  type ModelPrice,
  type ReconcileItem,
  type ReconcileStats,
  type ReconcileTask,
  type VendorBill
} from '@/api/reconcile'

const activeTab = ref('tasks')

// ---- 统计 ----
const stats = reactive<ReconcileStats>({ monthTasks: 0, diffItems: 0, disputeItems: 0, avgDiffRate: 0 })

// ---- 任务 ----
const taskStatuses = ['PENDING', 'RUNNING', 'COMPLETED', 'FAILED']
function taskStatusLabel(s: string) {
  return { PENDING: '待执行', RUNNING: '执行中', COMPLETED: '已完成', FAILED: '失败' }[s] || s
}
function statusType(s: string) {
  return { PENDING: 'warning', RUNNING: 'primary', COMPLETED: 'success', FAILED: 'danger' }[s] || 'info'
}
const loading = ref(false)
const taskList = ref<ReconcileTask[]>([])
const taskTotal = ref(0)
const query = reactive({ page: 1, size: 10, billDate: '', provider: '', status: '' })
const currentTask = ref<ReconcileTask>()

async function loadList() {
  loading.value = true
  try {
    const res = await listReconcileTasks({
      page: query.page,
      size: query.size,
      billDate: query.billDate || undefined,
      provider: query.provider || undefined,
      status: query.status || undefined
    })
    taskList.value = res.records
    taskTotal.value = res.total
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  const res = await getReconcileStats()
  Object.assign(stats, res)
}

// ---- 创建任务 ----
const createVisible = ref(false)
const createForm = reactive({ billDate: '', provider: '', remark: '' })

function openCreateDialog() {
  createForm.billDate = ''
  createForm.provider = ''
  createForm.remark = ''
  createVisible.value = true
}

async function handleCreateTask() {
  if (!createForm.billDate || !createForm.provider) {
    ElMessage.warning('请填写账单日期与供应商')
    return
  }
  await createReconcileTask({ billDate: createForm.billDate, provider: createForm.provider, remark: createForm.remark })
  ElMessage.success('创建成功')
  createVisible.value = false
  loadList()
}

async function handleExecute(row: ReconcileTask) {
  await ElMessageBox.confirm(`确认执行对账任务 ${row.taskCode}？`, '执行对账', { type: 'warning' })
  await executeReconcileTask(row.id!)
  ElMessage.success('执行完成')
  loadList()
  loadStats()
}

async function handleDelete(row: ReconcileTask) {
  await ElMessageBox.confirm(`确认删除任务 ${row.taskCode}？其明细将一并删除。`, '删除确认', { type: 'warning' })
  await deleteReconcileTask(row.id!)
  ElMessage.success('已删除')
  loadList()
  loadStats()
}

// ---- 对账明细 ----
const itemsVisible = ref(false)
const itemLoading = ref(false)
const itemList = ref<ReconcileItem[]>([])
const itemTotal = ref(0)
const itemQuery = reactive({ page: 1, size: 10, status: '', model: '' })
const itemStatuses = ['CONSISTENT', 'DIFFERENCE', 'PENDING', 'DISPUTED']
function itemStatusLabel(s: string) {
  return { CONSISTENT: '一致', DIFFERENCE: '差异', PENDING: '待处理', DISPUTED: '争议' }[s] || s
}
function itemStatusType(s: string) {
  return { CONSISTENT: 'success', DIFFERENCE: 'danger', PENDING: 'warning', DISPUTED: 'primary' }[s] || 'info'
}

function openItems(row: ReconcileTask) {
  currentTask.value = row
  itemsVisible.value = true
  itemQuery.page = 1
  itemQuery.status = ''
  itemQuery.model = ''
  loadItems()
}

async function loadItems() {
  if (!currentTask.value?.id) return
  itemLoading.value = true
  try {
    const res = await listReconcileItems(currentTask.value.id, {
      page: itemQuery.page,
      size: itemQuery.size,
      status: itemQuery.status || undefined,
      model: itemQuery.model || undefined
    })
    itemList.value = res.records
    itemTotal.value = res.total
  } finally {
    itemLoading.value = false
  }
}

async function handleDispute(row: ReconcileItem) {
  await ElMessageBox.confirm(`确认对模型「${row.model}」的差异发起争议？`, '发起争议', { type: 'warning' })
  await changeReconcileItemStatus(row.id!, 'DISPUTED', '管理员发起争议')
  ElMessage.success('已发起争议')
  loadItems()
  loadList()
  loadStats()
}

// ---- 供应商账单 ----
const billLoading = ref(false)
const billList = ref<VendorBill[]>([])
const billTotal = ref(0)
const billQuery = reactive({ page: 1, size: 10, billDate: '', provider: '', model: '' })

async function loadBills() {
  billLoading.value = true
  try {
    const res = await listVendorBills({
      page: billQuery.page,
      size: billQuery.size,
      billDate: billQuery.billDate || undefined,
      provider: billQuery.provider || undefined,
      model: billQuery.model || undefined
    })
    billList.value = res.records
    billTotal.value = res.total
  } finally {
    billLoading.value = false
  }
}

const billDialogVisible = ref(false)
const billForm = reactive<VendorBill>({})

function openBillDialog(row?: VendorBill) {
  Object.assign(billForm, row
    ? { ...row }
    : { id: undefined, billDate: '', provider: '', model: '', providerTokens: 0, providerCost: 0, remark: '', status: 'ACTIVE' })
  billDialogVisible.value = true
}

async function handleSaveBill() {
  if (!billForm.billDate || !billForm.provider || !billForm.model) {
    ElMessage.warning('请填写账单日期、供应商与模型')
    return
  }
  if (billForm.id) {
    await updateVendorBill(billForm.id, billForm)
  } else {
    await createVendorBill(billForm)
  }
  ElMessage.success('保存成功')
  billDialogVisible.value = false
  loadBills()
}

async function handleDeleteBill(row: VendorBill) {
  await ElMessageBox.confirm(`确认删除该账单（${row.provider} / ${row.model}）？`, '删除确认', { type: 'warning' })
  await deleteVendorBill(row.id!)
  ElMessage.success('已删除')
  loadBills()
}

// ---- 模型价格 ----
const priceLoading = ref(false)
const priceList = ref<ModelPrice[]>([])
const priceTotal = ref(0)
const priceQuery = reactive({ page: 1, size: 10, provider: '', model: '' })

async function loadPrices() {
  priceLoading.value = true
  try {
    const res = await listModelPrices({
      page: priceQuery.page,
      size: priceQuery.size,
      provider: priceQuery.provider || undefined,
      model: priceQuery.model || undefined
    })
    priceList.value = res.records
    priceTotal.value = res.total
  } finally {
    priceLoading.value = false
  }
}

const priceDialogVisible = ref(false)
// 表单用“元/百万 Token”录入，保存时自动折算为每 Token 单价；缓存单价可选（null 表示未配置）
const priceForm = reactive<ModelPrice & {
  inputPerMillion?: number
  outputPerMillion?: number
  cacheReadPerMillion?: number
  cacheWritePerMillion?: number
}>({})

function openPriceDialog(row?: ModelPrice) {
  Object.assign(priceForm, row
    ? {
        ...row,
        inputPerMillion: Number((row.inputPricePerToken || 0) * 1000000),
        outputPerMillion: Number((row.outputPricePerToken || 0) * 1000000),
        cacheReadPerMillion: row.cacheReadPricePerToken == null ? undefined : Number(row.cacheReadPricePerToken * 1000000),
        cacheWritePerMillion: row.cacheWritePricePerToken == null ? undefined : Number(row.cacheWritePricePerToken * 1000000)
      }
    : { id: undefined, provider: '', model: '', inputPerMillion: 0, outputPerMillion: 0, currency: 'CNY', status: 'ENABLED' })
  priceDialogVisible.value = true
}

async function handleSavePrice() {
  if (!priceForm.provider || !priceForm.model) {
    ElMessage.warning('请填写供应商与模型')
    return
  }
  // 元/百万 → 每 Token 单价（保留 10 位小数）；缓存单价留空存 null（按正常输入价计费）
  const payload = {
    ...priceForm,
    inputPricePerToken: Number(((priceForm.inputPerMillion || 0) / 1000000).toFixed(10)),
    outputPricePerToken: Number(((priceForm.outputPerMillion || 0) / 1000000).toFixed(10)),
    cacheReadPricePerToken: priceForm.cacheReadPerMillion == null
      ? null : Number(((priceForm.cacheReadPerMillion || 0) / 1000000).toFixed(10)),
    cacheWritePricePerToken: priceForm.cacheWritePerMillion == null
      ? null : Number(((priceForm.cacheWritePerMillion || 0) / 1000000).toFixed(10))
  }
  delete payload.inputPerMillion
  delete payload.outputPerMillion
  delete payload.cacheReadPerMillion
  delete payload.cacheWritePerMillion
  if (priceForm.id) {
    await updateModelPrice(priceForm.id, payload)
  } else {
    await createModelPrice(payload)
  }
  ElMessage.success('保存成功（只影响新调用，历史账单费用不变）')
  priceDialogVisible.value = false
  loadPrices()
}

async function handleTogglePrice(row: ModelPrice) {
  await changeModelPriceStatus(row.id!, row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED')
  ElMessage.success('已更新')
  loadPrices()
}

async function handleDeletePrice(row: ModelPrice) {
  await ElMessageBox.confirm(`确认删除价格（${row.provider} / ${row.model}）？`, '删除确认', { type: 'warning' })
  await deleteModelPrice(row.id!)
  ElMessage.success('已删除')
  loadPrices()
}

// ---- 工具 ----
function fmtSigned(n?: number) {
  if (n === undefined || n === null) return '-'
  return `${n > 0 ? '+' : ''}${n.toLocaleString()}`
}

onMounted(() => {
  loadStats()
  loadList()
  loadBills()
  loadPrices()
})
</script>

<style scoped lang="scss">
.stat-row {
  margin-bottom: 16px;

  .stat {
    text-align: center;
    padding: 8px 0;

    .value {
      font-size: 26px;
      font-weight: 700;
    }

    .primary {
      color: #409eff;
    }

    .danger {
      color: #f56c6c;
    }

    .warn {
      color: #e6a23c;
    }

    .label {
      margin-top: 6px;
      color: #909399;
      font-size: 13px;
    }
  }
}

.toolbar {
  margin-bottom: 14px;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.pagination {
  margin-top: 14px;
  justify-content: flex-end;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
  margin-top: 2px;
}

.danger-text {
  color: #f56c6c;
  font-weight: 600;
}
</style>
