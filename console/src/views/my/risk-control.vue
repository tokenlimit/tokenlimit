<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="header">
          <span class="title">我的资产与风控</span>
          <span class="desc">自主设置 API Key 限额，防范异常调用风险</span>
        </div>
      </template>

      <!-- 资产概览 -->
      <div class="asset-overview">
        <div class="stat-card">
          <div class="stat-label">本月可用配额</div>
          <div class="stat-value">{{ formatNumber(myOverview.personalQuota - myOverview.personalUsed) }}</div>
          <div class="stat-sub">总额度 {{ formatNumber(myOverview.personalQuota) }} | 已用 {{ formatNumber(myOverview.personalUsed) }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">团队预算剩余</div>
          <div class="stat-value primary">{{ formatNumber(myOverview.teamQuota - myOverview.teamUsed) }}</div>
          <div class="stat-sub">总预算 {{ formatNumber(myOverview.teamQuota) }} | 已用 {{ formatNumber(myOverview.teamUsed) }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">今日消耗</div>
          <div class="stat-value">{{ formatNumber(myOverview.todayTokens) }}</div>
          <div class="stat-sub">调用次数 {{ myOverview.todayCalls }}</div>
        </div>
      </div>

      <!-- API Key 风控策略列表 -->
      <el-table :data="policies" v-loading="loading" style="margin-top: 20px;">
        <el-table-column prop="keyId" label="API Key" width="180" />
        <el-table-column label="单次限额" width="150">
          <template #default="{ row }">
            <span v-if="row.maxTokensPerRequest">{{ formatNumber(row.maxTokensPerRequest) }}</span>
            <span v-else class="text-muted">不限制</span>
          </template>
        </el-table-column>
        <el-table-column label="小时限额" width="150">
          <template #default="{ row }">
            <div v-if="row.hourlyLimit">
              <span>{{ formatNumber(row.hourlyLimit) }}</span>
              <span class="text-muted">/ {{ formatNumber(row.hourlyUsed || 0) }}</span>
            </div>
            <span v-else class="text-muted">不限制</span>
          </template>
        </el-table-column>
        <el-table-column label="日限额" width="150">
          <template #default="{ row }">
            <div v-if="row.dailyLimit">
              <span>{{ formatNumber(row.dailyLimit) }}</span>
              <span class="text-muted">/ {{ formatNumber(row.dailyUsed || 0) }}</span>
            </div>
            <span v-else class="text-muted">不限制</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.isFrozen ? 'danger' : 'success'" size="small">
              {{ row.isFrozen ? '已冻结' : '正常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="200">
          <template #default="{ row }">
            <el-button type="primary" link @click="openPolicyDialog(row)">设置限额</el-button>
            <el-button type="danger" link @click="toggleFreeze(row)" :disabled="row.isFrozen">
              {{ row.isFrozen ? '已冻结' : '冻结' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 设置限额对话框 -->
    <el-dialog v-model="dialogVisible" title="设置 API Key 限额" width="500px">
      <el-form :model="policyForm" label-width="120px">
        <el-form-item label="单次请求限额">
          <el-input-number v-model="policyForm.maxTokensPerRequest" :min="0" :placeholder="undefined" 
                           controls-position="right" style="width: 100%;" />
          <div class="form-tip">单次请求最大 token 数，留空表示不限制</div>
        </el-form-item>
        <el-form-item label="小时限额">
          <el-input-number v-model="policyForm.hourlyLimit" :min="0" :placeholder="undefined" 
                           controls-position="right" style="width: 100%;" />
          <div class="form-tip">每小时最大 token 消耗，触发后自动熔断</div>
        </el-form-item>
        <el-form-item label="日限额">
          <el-input-number v-model="policyForm.dailyLimit" :min="0" :placeholder="undefined" 
                           controls-position="right" style="width: 100%;" />
          <div class="form-tip">每日最大 token 消耗</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="savePolicy" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMyPolicies, updatePolicy, freezeApiKey } from '@/api/userPolicy'
import { getMyOverview } from '@/api/my'
import type { ApiKeyPolicy, UpdatePolicyRequest } from '@/api/userPolicy'
import type { MyOverview } from '@/api/my'

const loading = ref(false)
const saving = ref(false)
const policies = ref<ApiKeyPolicy[]>([])
const myOverview = ref<MyOverview>({
  personalQuota: 0,
  personalUsed: 0,
  teamQuota: 0,
  teamUsed: 0,
  todayTokens: 0,
  todayCalls: 0,
  monthTokens: 0,
  monthCost: 0
})

const dialogVisible = ref(false)
const currentAccessKey = ref('')
const policyForm = reactive<UpdatePolicyRequest>({
  maxTokensPerRequest: null,
  hourlyLimit: null,
  dailyLimit: null
})

function formatNumber(num?: number | null): string {
  if (num === null || num === undefined) return '-'
  return num.toLocaleString()
}

async function loadPolicies() {
  loading.value = true
  try {
    policies.value = await getMyPolicies()
  } finally {
    loading.value = false
  }
}

async function loadOverview() {
  try {
    myOverview.value = await getMyOverview()
  } catch (e) {
    // ignore
  }
}

function openPolicyDialog(row: ApiKeyPolicy) {
  currentAccessKey.value = row.accessKey
  policyForm.maxTokensPerRequest = row.maxTokensPerRequest ?? null
  policyForm.hourlyLimit = row.hourlyLimit ?? null
  policyForm.dailyLimit = row.dailyLimit ?? null
  dialogVisible.value = true
}

async function savePolicy() {
  saving.value = true
  try {
    await updatePolicy(currentAccessKey.value, policyForm)
    ElMessage.success('设置成功')
    dialogVisible.value = false
    await loadPolicies()
  } finally {
    saving.value = false
  }
}

async function toggleFreeze(row: ApiKeyPolicy) {
  try {
    await ElMessageBox.confirm('确定要冻结该 API Key 吗？冻结后将无法调用。', '确认冻结', {
      type: 'warning'
    })
    await freezeApiKey(row.accessKey, { frozen: true, reason: '用户手动冻结' })
    ElMessage.success('已冻结')
    await loadPolicies()
  } catch (e) {
    // cancelled
  }
}

onMounted(() => {
  loadPolicies()
  loadOverview()
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

.asset-overview {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-top: 12px;

  .stat-card {
    background: linear-gradient(135deg, #f5f7fa 0%, #e4e7ed 100%);
    border-radius: 12px;
    padding: 16px;

    .stat-label {
      font-size: 13px;
      color: #606266;
      margin-bottom: 8px;
    }

    .stat-value {
      font-size: 24px;
      font-weight: 700;
      color: #303133;

      &.primary {
        color: var(--el-color-primary);
      }
    }

    .stat-sub {
      font-size: 12px;
      color: #909399;
      margin-top: 6px;
    }
  }
}

.text-muted {
  color: #909399;
  font-size: 12px;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
</style>
