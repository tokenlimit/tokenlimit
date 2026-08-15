<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="header">
          <span class="title">我的概览</span>
          <span class="desc">个人额度、用量与团队配额总览</span>
        </div>
      </template>

      <div v-if="overview" class="info">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="用户名">{{ overview.userName }}</el-descriptions-item>
          <el-descriptions-item label="用户编码">{{ overview.userCode }}</el-descriptions-item>
          <el-descriptions-item label="额度模式">{{ quotaModeText }}</el-descriptions-item>
          <el-descriptions-item label="团队">{{ overview.teamCode }}{{ overview.teamName ? `（${overview.teamName}）` : '' }}</el-descriptions-item>
          <el-descriptions-item label="角色">{{ roleText }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <el-row :gutter="16" class="stats">
        <el-col :span="6">
          <el-card shadow="never" class="stat-card">
            <div class="stat-label">今日 Tokens</div>
            <div class="stat-value primary">{{ fmt(overview?.todayTokens) }}</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="stat-card">
            <div class="stat-label">今日调用次数</div>
            <div class="stat-value primary">{{ fmt(overview?.todayCalls) }}</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="stat-card">
            <div class="stat-label">本月 Tokens</div>
            <div class="stat-value">{{ fmt(overview?.monthTokens) }}</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="stat-card">
            <div class="stat-label">本月费用（元）</div>
            <div class="stat-value warning">{{ Number(overview?.monthCost || 0).toFixed(2) }}</div>
          </el-card>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-card shadow="never" class="quota-card">
            <template #header>个人额度（{{ quotaModeText }}）</template>
            <el-progress
              :percentage="personalPercent"
              :color="personalPercent > 90 ? '#f56c6c' : '#409eff'"
              :stroke-width="18"
              :format="() => `${fmt(overview?.personalUsed)} / ${fmt(overview?.personalQuota)}`"
            />
            <div class="quota-note">剩余 {{ fmt(overview?.personalQuota! - overview?.personalUsed!) }}</div>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card shadow="never" class="quota-card">
            <template #header>团队配额</template>
            <el-progress
              :percentage="teamPercent"
              :color="teamPercent > 90 ? '#f56c6c' : '#67c23a'"
              :stroke-width="18"
              :format="() => `${fmt(overview?.teamUsed)} / ${fmt(overview?.teamQuota)}`"
            />
            <div class="quota-note">剩余 {{ fmt(overview?.teamQuota! - overview?.teamUsed!) }}</div>
          </el-card>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getMyOverview, type MyOverview } from '@/api/my'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const overview = ref<MyOverview>()

const quotaModeText = computed(() => {
  const map: Record<string, string> = {
    PERSONAL_ONLY: '仅个人',
    TEAM_ONLY: '仅团队',
    PERSONAL_FIRST_THEN_TEAM: '个人优先，再团队'
  }
  return map[overview.value?.quotaMode || ''] || overview.value?.quotaMode || '-'
})

const roleText = computed(() => {
  const map: Record<string, string> = {
    ADMIN: '全局管理员',
    TEAM_ADMIN: 'Team 管理员',
    USER: '普通用户'
  }
  return map[userStore.role || ''] || userStore.role || '-'
})

const personalPercent = computed(() => {
  const q = overview.value?.personalQuota || 0
  if (q <= 0) return 0
  return Math.min(100, Math.round(((overview.value?.personalUsed || 0) / q) * 100))
})
const teamPercent = computed(() => {
  const q = overview.value?.teamQuota || 0
  if (q <= 0) return 0
  return Math.min(100, Math.round(((overview.value?.teamUsed || 0) / q) * 100))
})

function fmt(v?: number) {
  return v == null ? '0' : Number(v).toLocaleString()
}

onMounted(async () => {
  overview.value = await getMyOverview()
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

.info {
  margin-bottom: 16px;
}

.stats {
  margin-bottom: 16px;

  .stat-card {
    .stat-label {
      color: #909399;
      font-size: 13px;
      margin-bottom: 8px;
    }

    .stat-value {
      font-size: 22px;
      font-weight: 600;

      &.primary {
        color: #409eff;
      }

      &.warning {
        color: #e6a23c;
      }
    }
  }
}

.quota-card {
  .quota-note {
    margin-top: 8px;
    color: #909399;
    font-size: 13px;
  }
}
</style>
